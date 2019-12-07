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

package net.fabricmc.loom.providers;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Consumer;

import com.google.common.net.UrlEscapers;
import org.apache.commons.io.FileUtils;
import org.apache.tools.ant.util.StringUtils;
import org.bitbucket.cowwoc.diffmatchpatch.DiffMatchPatch;
import org.gradle.api.Project;
import org.zeroturnaround.zip.FileSource;
import org.zeroturnaround.zip.ZipEntrySource;
import org.zeroturnaround.zip.ZipUtil;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.DependencyProvider;
import net.fabricmc.loom.util.DownloadUtil;
import net.fabricmc.mapping.reader.v2.TinyV2Factory;
import net.fabricmc.mapping.tree.TinyTree;
import net.fabricmc.stitch.Command;
import net.fabricmc.stitch.commands.CommandProposeFieldNames;
import net.fabricmc.stitch.commands.tinyv2.CommandMergeTinyV2;
import net.fabricmc.stitch.commands.tinyv2.CommandReorderTinyV2;

public class MappingsProvider extends DependencyProvider {
	public MinecraftMappedProvider mappedProvider;

	public String mappingsName;
	public String minecraftVersion;
	public String mappingsVersion;

	private Path mappingsDir;
	private Path mappingsStepsDir;
	// The mappings that gradle gives us
	private Path baseTinyMappings;
	// The mappings we use in practice
	public File tinyMappings;
	public File tinyMappingsJar;
	public File mappingsMixinExport;

	public void clean() throws IOException {
		FileUtils.deleteDirectory(mappingsDir.toFile());
	}

	public TinyTree getMappings() throws IOException {
		return MappingsCache.INSTANCE.get(tinyMappings.toPath());
	}

	@Override
	public void provide(DependencyInfo dependency, Project project, LoomGradleExtension extension, Consumer<Runnable> postPopulationScheduler) throws Exception {
		MinecraftProvider minecraftProvider = getDependencyManager().getProvider(MinecraftProvider.class);

		project.getLogger().lifecycle(":setting up mappings (" + dependency.getDependency().getName() + " " + dependency.getResolvedVersion() + ")");

		String version = dependency.getResolvedVersion();
		File mappingsJar = dependency.resolveFile().orElseThrow(() -> new RuntimeException("Could not find yarn mappings: " + dependency));

		this.mappingsName = StringUtils.removeSuffix(dependency.getDependency().getGroup() + "." + dependency.getDependency().getName(), "-unmerged");

		boolean isV2 = doesJarContainV2Mappings(mappingsJar.toPath());

		this.minecraftVersion = minecraftProvider.minecraftVersion;
		this.mappingsVersion = version + (isV2 ? "-v2" : "");

		initFiles(project);

		Files.createDirectories(mappingsDir);
		Files.createDirectories(mappingsStepsDir);

		String[] depStringSplit = dependency.getDepString().split(":");
		String jarClassifier = "final";

		if (depStringSplit.length >= 4) {
			jarClassifier = jarClassifier + depStringSplit[3];
		}

		tinyMappings = mappingsDir.resolve(StringUtils.removeSuffix(mappingsJar.getName(), ".jar") + ".tiny").toFile();
		tinyMappingsJar = new File(extension.getUserCache(), mappingsJar.getName().replace(".jar", "-" + jarClassifier + ".jar"));

		if (!tinyMappings.exists()) {
			storeMappings(project, extension, minecraftProvider, mappingsJar.toPath());
		}

		if (!tinyMappingsJar.exists()) {
			ZipUtil.pack(new ZipEntrySource[] {new FileSource("mappings/mappings.tiny", tinyMappings)}, tinyMappingsJar);
		}

		addDependency(tinyMappingsJar, project, Constants.MAPPINGS_FINAL);

		mappedProvider = new MinecraftMappedProvider();
		mappedProvider.initFiles(project, minecraftProvider, this);
		mappedProvider.provide(dependency, project, extension, postPopulationScheduler);
	}

	private void storeMappings(Project project, LoomGradleExtension extension, MinecraftProvider minecraftProvider, Path yarnJar) throws IOException {
		project.getLogger().lifecycle(":extracting " + yarnJar.getFileName());

		try (FileSystem fileSystem = FileSystems.newFileSystem(yarnJar, null)) {
			extractMappings(fileSystem, baseTinyMappings);
			patchMappings(project, baseTinyMappings, extension);
		}

		if (baseMappingsAreV2()) {
			// These are unmerged v2 mappings

			// Download and extract intermediary
			String encodedMinecraftVersion = UrlEscapers.urlFragmentEscaper().escape(minecraftVersion);
			String intermediaryArtifactUrl = "https://maven.fabricmc.net/net/fabricmc/intermediary/" + encodedMinecraftVersion + "/intermediary-" + encodedMinecraftVersion + "-v2.jar";
			Path intermediaryJar = mappingsStepsDir.resolve("v2-intermediary-" + minecraftVersion + ".jar");
			DownloadUtil.downloadIfChanged(new URL(intermediaryArtifactUrl), intermediaryJar.toFile(), project.getLogger());

			mergeAndSaveMappings(project, intermediaryJar, yarnJar);
		} else {
			// These are merged v1 mappings
			if (tinyMappings.exists()) {
				tinyMappings.delete();
			}

			project.getLogger().lifecycle(":populating field names");
			suggestFieldNames(minecraftProvider, baseTinyMappings, tinyMappings.toPath());
		}
	}

	private void patchMappings(Project project, Path baseTinyMappings, LoomGradleExtension extension) throws IOException {
		project.getLogger().lifecycle(":patching mappings");
		DiffMatchPatch dmp = new DiffMatchPatch();
		String current = String.join("\n", Files.readAllLines(baseTinyMappings));

		for (Object patchFileObj : extension.mappingPatches) {
			File patchFile = project.file(patchFileObj);
			// todo: check if it works with multiple patches
			List<DiffMatchPatch.Patch> patches = dmp.patchFromText(String.join("\n", FileUtils.readFileToString(patchFile, StandardCharsets.UTF_8)));
			current = dmp.patchApply(new LinkedList<>(patches), current)[0].toString();
		}

		Files.write(baseTinyMappings, Arrays.asList(current.split("\n")), StandardCharsets.UTF_8);
	}

	private boolean baseMappingsAreV2() throws IOException {
		try (BufferedReader reader = Files.newBufferedReader(baseTinyMappings)) {
			TinyV2Factory.readMetadata(reader);
			return true;
		} catch (IllegalArgumentException e) {
			// TODO: just check the mappings version when Parser supports V1 in readMetadata()
			return false;
		}
	}

	private boolean doesJarContainV2Mappings(Path path) throws IOException {
		try (FileSystem fs = FileSystems.newFileSystem(path, null)) {
			try (BufferedReader reader = Files.newBufferedReader(fs.getPath("mappings", "mappings.tiny"))) {
				TinyV2Factory.readMetadata(reader);
				return true;
			} catch (IllegalArgumentException e) {
				return false;
			}
		}
	}

	public static void extractMappings(FileSystem jar, Path extractTo) throws IOException {
		Files.copy(jar.getPath("mappings/mappings.tiny"), extractTo, StandardCopyOption.REPLACE_EXISTING);
	}

	private void mergeAndSaveMappings(Project project, Path unmergedIntermediaryJar, Path unmergedYarnJar) throws IOException {
		Path unmergedIntermediary = Paths.get(mappingsStepsDir.toString(), "unmerged-intermediary.tiny");
		project.getLogger().info(":extracting " + unmergedIntermediaryJar.getFileName());

		try (FileSystem unmergedIntermediaryFs = FileSystems.newFileSystem(unmergedIntermediaryJar, null)) {
			extractMappings(unmergedIntermediaryFs, unmergedIntermediary);
		}

		Path unmergedYarn = Paths.get(mappingsStepsDir.toString(), "unmerged-yarn.tiny");
		project.getLogger().info(":extracting " + unmergedYarnJar.getFileName());

		try (FileSystem unmergedYarnJarFs = FileSystems.newFileSystem(unmergedYarnJar, null)) {
			extractMappings(unmergedYarnJarFs, unmergedYarn);
		}

		Path invertedIntermediary = Paths.get(mappingsStepsDir.toString(), "inverted-intermediary.tiny");
		reorderMappings(unmergedIntermediary, invertedIntermediary, "intermediary", "official");
		Path unorderedMergedMappings = Paths.get(mappingsStepsDir.toString(), "unordered-merged.tiny");
		project.getLogger().info(":merging");
		mergeMappings(invertedIntermediary, unmergedYarn, unorderedMergedMappings);
		reorderMappings(unorderedMergedMappings, tinyMappings.toPath(), "official", "intermediary", "named");
	}

	private void reorderMappings(Path oldMappings, Path newMappings, String... newOrder) {
		Command command = new CommandReorderTinyV2();
		String[] args = new String[2 + newOrder.length];
		args[0] = oldMappings.toAbsolutePath().toString();
		args[1] = newMappings.toAbsolutePath().toString();
		System.arraycopy(newOrder, 0, args, 2, newOrder.length);
		runCommand(command, args);
	}

	private void mergeMappings(Path intermediaryMappings, Path yarnMappings, Path newMergedMappings) {
		try {
			Command command = new CommandMergeTinyV2();
			runCommand(command, intermediaryMappings.toAbsolutePath().toString(),
							yarnMappings.toAbsolutePath().toString(),
							newMergedMappings.toAbsolutePath().toString(),
							"intermediary", "official");
		} catch (Exception e) {
			throw new RuntimeException("Could not merge mappings from " + intermediaryMappings.toString()
							+ " with mappings from " + yarnMappings, e);
		}
	}

	private void suggestFieldNames(MinecraftProvider minecraftProvider, Path oldMappings, Path newMappings) {
		Command command = new CommandProposeFieldNames();
		runCommand(command, minecraftProvider.MINECRAFT_MERGED_JAR.getAbsolutePath(),
						oldMappings.toAbsolutePath().toString(),
						newMappings.toAbsolutePath().toString());
	}

	private void runCommand(Command command, String... args) {
		try {
			command.run(args);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private void initFiles(Project project) {
		LoomGradleExtension extension = project.getExtensions().getByType(LoomGradleExtension.class);
		mappingsDir = extension.getUserCache().toPath().resolve("mappings");
		mappingsStepsDir = mappingsDir.resolve("steps");

		baseTinyMappings = mappingsDir.resolve(mappingsName + "-tiny-" + minecraftVersion + "-" + mappingsVersion + "-base");
		mappingsMixinExport = new File(extension.getProjectBuildCache(), "mixin-map-" + minecraftVersion + "-" + mappingsVersion + ".tiny");
	}

	@Override
	public String getTargetConfig() {
		return Constants.MAPPINGS;
	}
}
