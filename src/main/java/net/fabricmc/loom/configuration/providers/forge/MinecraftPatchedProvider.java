/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2016, 2017, 2018 FabricMC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package net.fabricmc.loom.configuration.providers.forge;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import de.oceanlabs.mcp.mcinjector.adaptors.ParameterAnnotationFixer;
import net.minecraftforge.accesstransformer.TransformerProcessor;
import net.minecraftforge.binarypatcher.ConsoleTool;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.output.NullOutputStream;
import org.gradle.api.Project;
import org.gradle.api.logging.Logger;
import org.gradle.api.plugins.JavaPluginConvention;
import org.gradle.api.tasks.SourceSet;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

import net.fabricmc.loom.configuration.DependencyProvider;
import net.fabricmc.loom.configuration.providers.MinecraftProvider;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftMappedProvider;
import net.fabricmc.loom.util.Checksum;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.DownloadUtil;
import net.fabricmc.loom.util.JarUtil;
import net.fabricmc.loom.util.TinyRemapperMappingsHelper;
import net.fabricmc.loom.util.function.FsPathConsumer;
import net.fabricmc.loom.util.srg.SpecialSourceExecutor;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;

public class MinecraftPatchedProvider extends DependencyProvider {
	private File minecraftClientSrgJar;
	private File minecraftServerSrgJar;
	private File minecraftClientPatchedSrgJar;
	private File minecraftServerPatchedSrgJar;
	private File minecraftClientPatchedJar;
	private File minecraftServerPatchedJar;
	private File minecraftMergedPatchedJar;
	private Path projectAtHash;
	@Nullable
	private Path projectAt = null;
	private boolean atDirty = false;

	public MinecraftPatchedProvider(Project project) {
		super(project);
	}

	@Override
	public void provide(DependencyInfo dependency, Consumer<Runnable> postPopulationScheduler) throws Exception {
		initFiles();

		if (atDirty) {
			getProject().getLogger().lifecycle(":found dirty access transformers");
		}

		if (atDirty || !minecraftClientPatchedJar.exists() || !minecraftServerPatchedJar.exists()) {
			if (!minecraftClientSrgJar.exists() || !minecraftServerSrgJar.exists()) {
				createSrgJars(getProject().getLogger());
			}

			if (atDirty || !minecraftClientPatchedSrgJar.exists() || !minecraftServerPatchedSrgJar.exists()) {
				patchJars(getProject().getLogger());
				injectForgeClasses(getProject().getLogger());
			}

			remapPatchedJars(getProject().getLogger());
		}

		if (atDirty || !minecraftMergedPatchedJar.exists()) {
			mergeJars(getProject().getLogger());
		}
	}

	public void initFiles() throws IOException {
		projectAtHash = getExtension().getProjectPersistentCache().toPath().resolve("at.sha256");

		SourceSet main = getProject().getConvention().findPlugin(JavaPluginConvention.class).getSourceSets().getByName("main");

		for (File srcDir : main.getResources().getSrcDirs()) {
			Path projectAt = srcDir.toPath().resolve("META-INF").resolve("accesstransformer.cfg");

			if (Files.exists(projectAt)) {
				this.projectAt = projectAt;
				break;
			}
		}

		if (isRefreshDeps() || Files.notExists(projectAtHash)) {
			writeAtHash();
			atDirty = projectAt != null;
		} else {
			byte[] expected = Files.readAllBytes(projectAtHash);
			byte[] current = projectAt != null ? Checksum.sha256(projectAt.toFile()) : Checksum.sha256("");
			boolean mismatched = !Arrays.equals(current, expected);

			if (mismatched) {
				writeAtHash();
			}

			atDirty = mismatched && projectAt != null;
		}

		MinecraftProvider minecraftProvider = getExtension().getMinecraftProvider();
		PatchProvider patchProvider = getExtension().getPatchProvider();
		String minecraftVersion = minecraftProvider.getMinecraftVersion();
		String jarSuffix = "-patched-forge-" + patchProvider.forgeVersion;
		minecraftProvider.setJarSuffix(jarSuffix);

		File cache = usesProjectCache() ? getExtension().getProjectPersistentCache() : getExtension().getUserCache();

		minecraftClientPatchedJar = new File(cache, "minecraft-" + minecraftVersion + "-client" + jarSuffix + ".jar");
		minecraftServerPatchedJar = new File(cache, "minecraft-" + minecraftVersion + "-server" + jarSuffix + ".jar");
		minecraftClientSrgJar = new File(cache, "minecraft-" + minecraftVersion + "-client-srg.jar");
		minecraftServerSrgJar = new File(cache, "minecraft-" + minecraftVersion + "-server-srg.jar");
		minecraftClientPatchedSrgJar = new File(cache, "minecraft-" + minecraftVersion + "-client-srg" + jarSuffix + ".jar");
		minecraftServerPatchedSrgJar = new File(cache, "minecraft-" + minecraftVersion + "-server-srg" + jarSuffix + ".jar");
		minecraftMergedPatchedJar = new File(cache, "minecraft-" + minecraftVersion + "-merged" + jarSuffix + ".jar");

		if (isRefreshDeps()) {
			cleanCache();
		}

		if (!minecraftClientSrgJar.exists() || !minecraftServerSrgJar.exists()) {
			minecraftClientPatchedJar.delete();
			minecraftServerPatchedJar.delete();
		}
	}

	public void cleanCache() throws IOException {
		Files.deleteIfExists(projectAtHash);

		for (File file : Arrays.asList(
				minecraftClientSrgJar,
				minecraftServerSrgJar,
				minecraftClientPatchedSrgJar,
				minecraftServerPatchedSrgJar,
				minecraftClientPatchedJar,
				minecraftServerPatchedJar,
				minecraftMergedPatchedJar
		)) {
			Files.deleteIfExists(file.toPath());
		}
	}

	private void writeAtHash() throws IOException {
		try (OutputStream out = Files.newOutputStream(projectAtHash)) {
			if (projectAt != null) {
				out.write(Checksum.sha256(projectAt.toFile()));
			} else {
				out.write(Checksum.sha256(""));
			}
		}
	}

	private void createSrgJars(Logger logger) throws Exception {
		logger.lifecycle(":remapping minecraft (SpecialSource, official -> srg)");

		McpConfigProvider mcpProvider = getExtension().getMcpConfigProvider();
		MinecraftProvider minecraftProvider = getExtension().getMinecraftProvider();

		Path srg = mcpProvider.getSrg().toPath();
		File specialSourceJar = new File(getExtension().getUserCache(), "SpecialSource-1.8.3-shaded.jar");
		DownloadUtil.downloadIfChanged(new URL("https://repo1.maven.org/maven2/net/md-5/SpecialSource/1.8.3/SpecialSource-1.8.3-shaded.jar"), specialSourceJar, getProject().getLogger(), true);

		Files.copy(SpecialSourceExecutor.produceSrgJar(getProject(), specialSourceJar, minecraftProvider.minecraftClientJar.toPath(), srg), minecraftClientSrgJar.toPath());
		Files.copy(SpecialSourceExecutor.produceSrgJar(getProject(), specialSourceJar, minecraftProvider.minecraftServerJar.toPath(), srg), minecraftServerSrgJar.toPath());
	}

	private void fixParameterAnnotation(File jarFile) throws Exception {
		getProject().getLogger().info(":fixing parameter annotations for " + jarFile.toString());

		try (FileSystem fs = JarUtil.fs(jarFile.toPath(), false)) {
			for (Path rootDir : fs.getRootDirectories()) {
				for (Path file : (Iterable<? extends Path>) Files.walk(rootDir)::iterator) {
					if (!file.toString().endsWith(".class")) {
						continue;
					}

					byte[] bytes = Files.readAllBytes(file);
					ClassReader reader = new ClassReader(bytes);
					ClassNode cn = new ClassNode();
					ClassVisitor visitor = new ParameterAnnotationFixer(cn, null);
					reader.accept(visitor, 0);

					ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
					cn.accept(writer);
					byte[] out = writer.toByteArray();

					if (!Arrays.equals(bytes, out)) {
						Files.delete(file);
						Files.write(file, out);
					}
				}
			}
		}
	}

	private void injectForgeClasses(Logger logger) throws IOException {
		logger.lifecycle(":injecting forge classes into minecraft");
		copyAll(getExtension().getForgeUniversalProvider().getForge(), minecraftClientPatchedSrgJar);
		copyAll(getExtension().getForgeUniversalProvider().getForge(), minecraftServerPatchedSrgJar);

		copyUserdevFiles(getExtension().getForgeUserdevProvider().getUserdevJar(), minecraftClientPatchedSrgJar);
		copyUserdevFiles(getExtension().getForgeUserdevProvider().getUserdevJar(), minecraftServerPatchedSrgJar);

		logger.lifecycle(":injecting loom classes into minecraft");
		File injection = File.createTempFile("loom-injection", ".jar");

		try (InputStream in = MinecraftProvider.class.getResourceAsStream("/inject/injection.jar")) {
			FileUtils.copyInputStreamToFile(in, injection);
		}

		walkFileSystems(injection, minecraftClientPatchedSrgJar, it -> !it.getFileName().toString().equals("MANIFEST.MF"), this::copyReplacing);
		walkFileSystems(injection, minecraftServerPatchedSrgJar, it -> !it.getFileName().toString().equals("MANIFEST.MF"), this::copyReplacing);

		logger.lifecycle(":access transforming minecraft");

		boolean[] bools = { true, false };

		for (boolean isClient : bools) {
			String side = isClient ? "client" : "server";
			File target = isClient ? minecraftClientPatchedSrgJar : minecraftServerPatchedSrgJar;

			File atJar = File.createTempFile("at" + side, ".jar");
			File at = File.createTempFile("at" + side, ".cfg");
			FileUtils.copyFile(target, atJar);
			JarUtil.extract(atJar, "META-INF/accesstransformer.cfg", at);
			String[] args = new String[] {
					"--inJar", atJar.getAbsolutePath(),
					"--outJar", target.getAbsolutePath(),
					"--atFile", at.getAbsolutePath()
			};

			if (projectAt != null) {
				args = Arrays.copyOf(args, args.length + 2);
				args[args.length - 2] = "--atFile";
				args[args.length - 1] = projectAt.toAbsolutePath().toString();
			}

			TransformerProcessor.main(args);
		}
	}

	private void remapPatchedJars(Logger logger) throws Exception {
		boolean[] bools = { true, false };

		ExecutorService service = Executors.newFixedThreadPool(2);
		List<Future<?>> futures = new LinkedList<>();

		for (boolean isClient : bools) {
			futures.add(service.submit(() -> {
				try {
					logger.lifecycle(":remapping minecraft (TinyRemapper, " + (isClient ? "client" : "server") + ", srg -> official)");

					TinyRemapper remapper = TinyRemapper.newRemapper()
							.withMappings(TinyRemapperMappingsHelper.create(getExtension().getMappingsProvider().getMappingsWithSrg(), "srg", "official", true))
							.renameInvalidLocals(true)
							.rebuildSourceFilenames(true)
							.build();

					Path input = (isClient ? minecraftClientPatchedSrgJar : minecraftServerPatchedSrgJar).toPath();
					Path output = (isClient ? minecraftClientPatchedJar : minecraftServerPatchedJar).toPath();

					try (OutputConsumerPath outputConsumer = new OutputConsumerPath.Builder(output).build()) {
						outputConsumer.addNonClassFiles(input);

						remapper.readClassPath(MinecraftMappedProvider.getRemapClasspath(getProject()));
						remapper.readInputs(input);
						remapper.apply(outputConsumer);
					} finally {
						remapper.finish();
					}
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			}));
		}

		for (Future<?> future : futures) {
			future.get();
		}
	}

	private void patchJars(Logger logger) throws Exception {
		logger.lifecycle(":patching jars");

		PatchProvider patchProvider = getExtension().getPatchProvider();
		patchJars(minecraftClientSrgJar, minecraftClientPatchedSrgJar, patchProvider.clientPatches);
		patchJars(minecraftServerSrgJar, minecraftServerPatchedSrgJar, patchProvider.serverPatches);

		logger.lifecycle(":copying missing classes into patched jars");
		copyMissingClasses(minecraftClientSrgJar, minecraftClientPatchedSrgJar);
		copyMissingClasses(minecraftServerSrgJar, minecraftServerPatchedSrgJar);

		fixParameterAnnotation(minecraftClientPatchedSrgJar);
		fixParameterAnnotation(minecraftServerPatchedSrgJar);
	}

	private void patchJars(File clean, File output, Path patches) throws IOException {
		PrintStream out = System.out;

		try {
			// Make binpatcher shut up
			System.setOut(new PrintStream(new NullOutputStream()));
		} catch (SecurityException ignored) {
			// ignored
		}

		ConsoleTool.main(new String[]{
				"--clean", clean.getAbsolutePath(),
				"--output", output.getAbsolutePath(),
				"--apply", patches.toAbsolutePath().toString()
		});

		try {
			System.setOut(out);
		} catch (SecurityException ignored) {
			// ignored
		}
	}

	private void mergeJars(Logger logger) throws IOException {
		// FIXME: Hack here: There are no server-only classes so we can just copy the client JAR.
		FileUtils.copyFile(minecraftClientPatchedJar, minecraftMergedPatchedJar);

		logger.lifecycle(":copying resources");

		// Copy resources
		MinecraftProvider minecraftProvider = getExtension().getMinecraftProvider();
		copyNonClassFiles(minecraftProvider.minecraftClientJar, minecraftMergedPatchedJar);
		copyNonClassFiles(minecraftProvider.minecraftServerJar, minecraftMergedPatchedJar);
	}

	private void walkFileSystems(File source, File target, Predicate<Path> filter, Function<FileSystem, Iterable<Path>> toWalk, FsPathConsumer action) throws IOException {
		try (FileSystem sourceFs = JarUtil.fs(source.toPath(), false);
				FileSystem targetFs = JarUtil.fs(target.toPath(), false)) {
			for (Path sourceDir : toWalk.apply(sourceFs)) {
				Path dir = sourceDir.toAbsolutePath();
				Files.walk(dir)
						.filter(Files::isRegularFile)
						.filter(filter)
						.forEach(it -> {
							boolean root = dir.getParent() == null;

							try {
								Path relativeSource = root ? it : dir.relativize(it);
								Path targetPath = targetFs.getPath(relativeSource.toString());
								action.accept(sourceFs, targetFs, it, targetPath);
							} catch (IOException e) {
								throw new UncheckedIOException(e);
							}
						});
			}
		}
	}

	private void walkFileSystems(File source, File target, Predicate<Path> filter, FsPathConsumer action) throws IOException {
		walkFileSystems(source, target, filter, FileSystem::getRootDirectories, action);
	}

	private void copyAll(File source, File target) throws IOException {
		walkFileSystems(source, target, it -> true, this::copyReplacing);
	}

	private void copyMissingClasses(File source, File target) throws IOException {
		walkFileSystems(source, target, it -> it.toString().endsWith(".class"), (sourceFs, targetFs, sourcePath, targetPath) -> {
			if (Files.exists(targetPath)) return;
			Path parent = targetPath.getParent();

			if (parent != null) {
				Files.createDirectories(parent);
			}

			Files.copy(sourcePath, targetPath);
		});
	}

	private void copyNonClassFiles(File source, File target) throws IOException {
		walkFileSystems(source, target, it -> !it.toString().endsWith(".class"), this::copyReplacing);
	}

	private void copyReplacing(FileSystem sourceFs, FileSystem targetFs, Path sourcePath, Path targetPath) throws IOException {
		Path parent = targetPath.getParent();

		if (parent != null) {
			Files.createDirectories(parent);
		}

		Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
	}

	private void copyUserdevFiles(File source, File target) throws IOException {
		walkFileSystems(source, target, file -> true, fs -> Collections.singleton(fs.getPath("inject")), (sourceFs, targetFs, sourcePath, targetPath) -> {
			Path parent = targetPath.getParent();

			if (parent != null) {
				Files.createDirectories(parent);
			}

			Files.copy(sourcePath, targetPath);
		});
	}

	public File getMergedJar() {
		return minecraftMergedPatchedJar;
	}

	public boolean usesProjectCache() {
		return projectAt != null;
	}

	public boolean isAtDirty() {
		return atDirty;
	}

	@Override
	public String getTargetConfig() {
		return Constants.Configurations.MINECRAFT;
	}
}
