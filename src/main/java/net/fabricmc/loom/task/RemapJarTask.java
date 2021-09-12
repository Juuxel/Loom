/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2016-2021 FabricMC
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

package net.fabricmc.loom.task;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import dev.architectury.refmapremapper.RefmapRemapper;
import dev.architectury.refmapremapper.remapper.MappingsRemapper;
import dev.architectury.refmapremapper.remapper.ReferenceRemapper;
import dev.architectury.refmapremapper.remapper.Remapper;
import dev.architectury.refmapremapper.remapper.SimpleReferenceRemapper;
import dev.architectury.tinyremapper.IMappingProvider;
import dev.architectury.tinyremapper.TinyRemapper;
import dev.architectury.tinyremapper.TinyUtils;
import dev.architectury.tinyremapper.extension.mixin.MixinExtension;
import org.cadixdev.at.AccessTransformSet;
import org.cadixdev.at.io.AccessTransformFormats;
import org.cadixdev.lorenz.MappingSet;
import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.jvm.tasks.Jar;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.zeroturnaround.zip.ZipUtil;
import org.zeroturnaround.zip.transform.StreamZipEntryTransformer;
import org.zeroturnaround.zip.transform.ZipEntryTransformerEntry;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.build.JarRemapper;
import net.fabricmc.loom.build.MixinRefmapHelper;
import net.fabricmc.loom.build.nesting.EmptyNestedJarProvider;
import net.fabricmc.loom.build.nesting.JarNester;
import net.fabricmc.loom.build.nesting.MergedNestedJarProvider;
import net.fabricmc.loom.build.nesting.NestedDependencyProvider;
import net.fabricmc.loom.build.nesting.NestedJarPathProvider;
import net.fabricmc.loom.build.nesting.NestedJarProvider;
import net.fabricmc.loom.configuration.JarManifestConfiguration;
import net.fabricmc.loom.configuration.accesswidener.AccessWidenerJarProcessor;
import net.fabricmc.loom.configuration.providers.mappings.MappingsProviderImpl;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.FileSystemUtil;
import net.fabricmc.loom.util.LfWriter;
import net.fabricmc.loom.util.SourceRemapper;
import net.fabricmc.loom.util.TinyRemapperMappingsHelper;
import net.fabricmc.loom.util.ZipReprocessorUtil;
import net.fabricmc.loom.util.aw2at.Aw2At;
import net.fabricmc.lorenztiny.TinyMappingsReader;
import net.fabricmc.mapping.tree.ClassDef;
import net.fabricmc.mapping.tree.FieldDef;
import net.fabricmc.mapping.tree.MethodDef;
import net.fabricmc.mapping.tree.TinyTree;
import net.fabricmc.stitch.util.Pair;

public class RemapJarTask extends Jar {
	private static final String MANIFEST_PATH = "META-INF/MANIFEST.MF";

	private final RegularFileProperty input;
	private final Property<Boolean> addNestedDependencies;
	private final Property<Boolean> addDefaultNestedDependencies;
	private final Property<Boolean> remapAccessWidener;
	private final List<Action<TinyRemapper.Builder>> remapOptions = new ArrayList<>();
	private final Property<String> fromM;
	private final Property<String> toM;
	private final SetProperty<String> atAccessWideners;
	public JarRemapper jarRemapper;
	private FileCollection classpath;
	private final Set<Object> nestedPaths = new LinkedHashSet<>();

	public RemapJarTask() {
		super();
		input = getProject().getObjects().fileProperty();
		addNestedDependencies = getProject().getObjects().property(Boolean.class);
		addDefaultNestedDependencies = getProject().getObjects().property(Boolean.class);
		remapAccessWidener = getProject().getObjects().property(Boolean.class);
		fromM = getProject().getObjects().property(String.class);
		toM = getProject().getObjects().property(String.class);
		atAccessWideners = getProject().getObjects().setProperty(String.class).empty();
		fromM.set("named");
		toM.set(SourceRemapper.intermediary(getProject()));
		// false by default, I have no idea why I have to do it for this property and not the other one
		remapAccessWidener.set(false);
		addDefaultNestedDependencies.set(true);

		if (!LoomGradleExtension.get(getProject()).getMixin().getUseLegacyMixinAp().get()) {
			remapOptions.add(b -> b.extension(new MixinExtension()));
		}
	}

	@TaskAction
	public void doTask() throws Throwable {
		boolean singleRemap = false;

		if (jarRemapper == null) {
			singleRemap = true;
			jarRemapper = new JarRemapper();
		}

		scheduleRemap(singleRemap || LoomGradleExtension.get(getProject()).isRootProject());

		if (singleRemap) {
			jarRemapper.remap(getProject());
		}

		convertAwToAt();
	}

	private ReferenceRemapper createReferenceRemapper(LoomGradleExtension extension, String from, String to) throws IOException {
		TinyTree mappings = extension.shouldGenerateSrgTiny() ? extension.getMappingsProvider().getMappingsWithSrg() : extension.getMappingsProvider().getMappings();

		return new SimpleReferenceRemapper(new SimpleReferenceRemapper.Remapper() {
			@Override
			@Nullable
			public String mapClass(String value) {
				return mappings.getClasses().stream()
						.filter(classDef -> Objects.equals(classDef.getName(from), value))
						.findFirst()
						.map(classDef -> classDef.getName(to))
						.orElse(null);
			}

			@Override
			@Nullable
			public String mapMethod(@Nullable String className, String methodName, String methodDescriptor) {
				if (className != null) {
					Optional<ClassDef> classDef = mappings.getClasses().stream()
							.filter(c -> Objects.equals(c.getName(from), className))
							.findFirst();

					if (classDef.isPresent()) {
						for (MethodDef methodDef : classDef.get().getMethods()) {
							if (Objects.equals(methodDef.getName(from), methodName) && Objects.equals(methodDef.getDescriptor(from), methodDescriptor)) {
								return methodDef.getName(to);
							}
						}
					}
				}

				return mappings.getClasses().stream()
						.flatMap(classDef -> classDef.getMethods().stream())
						.filter(methodDef -> Objects.equals(methodDef.getName(from), methodName) && Objects.equals(methodDef.getDescriptor(from), methodDescriptor))
						.findFirst()
						.map(methodDef -> methodDef.getName(to))
						.orElse(null);
			}

			@Override
			@Nullable
			public String mapField(@Nullable String className, String fieldName, String fieldDescriptor) {
				if (className != null) {
					Optional<ClassDef> classDef = mappings.getClasses().stream()
							.filter(c -> Objects.equals(c.getName(from), className))
							.findFirst();

					if (classDef.isPresent()) {
						for (FieldDef fieldDef : classDef.get().getFields()) {
							if (Objects.equals(fieldDef.getName(from), fieldName) && Objects.equals(fieldDef.getDescriptor(from), fieldDescriptor)) {
								return fieldDef.getName(to);
							}
						}
					}
				}

				return mappings.getClasses().stream()
						.flatMap(classDef -> classDef.getFields().stream())
						.filter(fieldDef -> Objects.equals(fieldDef.getName(from), fieldName) && Objects.equals(fieldDef.getDescriptor(from), fieldDescriptor))
						.findFirst()
						.map(fieldDef -> fieldDef.getName(to))
						.orElse(null);
			}
		});
	}

	public void scheduleRemap(boolean isMainRemapTask) throws Throwable {
		Project project = getProject();
		LoomGradleExtension extension = LoomGradleExtension.get(getProject());
		Path input = this.getInput().getAsFile().get().toPath();
		Path output = this.getArchivePath().toPath();

		if (!Files.exists(input)) {
			throw new FileNotFoundException(input.toString());
		}

		MappingsProviderImpl mappingsProvider = extension.getMappingsProvider();

		String fromM = this.fromM.get();
		String toM = this.toM.get();

		if (isMainRemapTask) {
			jarRemapper.addToClasspath(getRemapClasspath());

			jarRemapper.addMappings(TinyRemapperMappingsHelper.create(extension.shouldGenerateSrgTiny() ? mappingsProvider.getMappingsWithSrg() : mappingsProvider.getMappings(), fromM, toM, false));
		}

		for (File mixinMapFile : extension.getAllMixinMappings()) {
			if (mixinMapFile.exists()) {
				IMappingProvider provider = TinyUtils.createTinyMappingProvider(mixinMapFile.toPath(), fromM, "intermediary");
				jarRemapper.addMappings(!toM.equals("intermediary") ? remapToSrg(extension, provider, "intermediary", toM) : provider);
			}
		}

		// Add remap options to the jar remapper
		jarRemapper.addOptions(this.remapOptions);

		project.getLogger().info(":scheduling remap " + input.getFileName() + " from " + fromM + " to " + toM);

		NestedJarProvider nestedJarProvider = getNestedJarProvider();
		nestedJarProvider.prepare(getProject());

		jarRemapper.scheduleRemap(input, output)
				.supplyAccessWidener((remapData, remapper) -> {
					if (getRemapAccessWidener().getOrElse(false) && extension.getAccessWidenerPath().isPresent()) {
						AccessWidenerJarProcessor accessWidenerJarProcessor = extension.getJarProcessorManager().getByType(AccessWidenerJarProcessor.class);
						byte[] data;

						try {
							data = accessWidenerJarProcessor.getRemappedAccessWidener(remapper);
						} catch (IOException e) {
							throw new RuntimeException("Failed to remap access widener");
						}

						String awPath = accessWidenerJarProcessor.getAccessWidenerPath(remapData.input);
						Preconditions.checkNotNull(awPath, "Failed to find accessWidener in fabric.mod.json: " + remapData.input);

						return Pair.of(awPath, data);
					}

					return null;
				})
				.complete((data, accessWidener) -> {
					if (!Files.exists(output)) {
						throw new RuntimeException("Failed to remap " + input + " to " + output + " - file missing!");
					}

					if (extension.getMixin().getUseLegacyMixinAp().get()) {
						if (MixinRefmapHelper.addRefmapName(project, output)) {
							project.getLogger().debug("Transformed mixin reference maps in output JAR!");
						}

						if (!toM.equals("intermediary")) {
							try {
								remapRefmap(extension, output, "intermediary", toM);
							} catch (IOException e) {
								throw new RuntimeException("Failed to remap refmap jar", e);
							}
						}
					} else if (extension.isForge()) {
						throw new RuntimeException("Forge must have useLegacyMixinAp enabled");
					}

					if (getAddNestedDependencies().getOrElse(false)) {
						JarNester.nestJars(nestedJarProvider.provide(), output.toFile(), project.getLogger());
					}

					if (accessWidener != null) {
						boolean replaced = ZipUtil.replaceEntry(data.output.toFile(), accessWidener.getLeft(), accessWidener.getRight());
						Preconditions.checkArgument(replaced, "Failed to remap access widener");
					}

					if (!extension.isForge()) {
						// Add data to the manifest
						boolean transformed = ZipUtil.transformEntries(data.output.toFile(), new ZipEntryTransformerEntry[] {
								new ZipEntryTransformerEntry(MANIFEST_PATH, new StreamZipEntryTransformer() {
									@Override
									protected void transform(ZipEntry zipEntry, InputStream in, OutputStream out) throws IOException {
										var manifest = new Manifest(in);
										var manifestConfiguration = new JarManifestConfiguration(project);

										manifestConfiguration.configure(manifest);
										manifest.getMainAttributes().putValue("Fabric-Mapping-Namespace", toM);

										manifest.write(out);
									}
								})
						});
						Preconditions.checkArgument(transformed, "Failed to transform jar manifest");
					}

					if (isReproducibleFileOrder() || !isPreserveFileTimestamps()) {
						try {
							ZipReprocessorUtil.reprocessZip(output.toFile(), isReproducibleFileOrder(), isPreserveFileTimestamps());
						} catch (IOException e) {
							throw new RuntimeException("Failed to re-process jar", e);
						}
					}
				});
	}

	private void remapRefmap(LoomGradleExtension extension, Path output, String from, String to) throws IOException {
		try (FileSystem fs = FileSystems.newFileSystem(URI.create("jar:" + output.toUri()), ImmutableMap.of("create", false))) {
			Path refmapPath = fs.getPath(extension.getMixin().getDefaultRefmapName().get());

			if (Files.exists(refmapPath)) {
				try (Reader refmapReader = Files.newBufferedReader(refmapPath, StandardCharsets.UTF_8)) {
					Gson gson = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
					JsonObject refmapElement = gson.fromJson(refmapReader, JsonObject.class);
					refmapElement = RefmapRemapper.remap(new Remapper() {
						ReferenceRemapper remapper = createReferenceRemapper(extension, from, to);

						@Override
						@Nullable
						public MappingsRemapper remapMappings() {
							return className -> remapper;
						}

						@Override
						@Nullable
						public Map.Entry<String, @Nullable MappingsRemapper> remapMappingsData(String data) {
							if (Objects.equals(data, "named:intermediary")) {
								return new AbstractMap.SimpleEntry<>(Objects.equals(to, "srg") ? "searge" : data, remapMappings());
							}

							return null;
						}
					}, refmapElement);
					Files.delete(refmapPath);
					Files.write(refmapPath, gson.toJson(refmapElement).getBytes(StandardCharsets.UTF_8));
				}
			}
		}
	}

	private NestedJarProvider getNestedJarProvider() {
		if (!LoomGradleExtension.get(getProject()).supportsInclude()) {
			return EmptyNestedJarProvider.INSTANCE;
		}

		Configuration includeConfiguration = getProject().getConfigurations().getByName(Constants.Configurations.INCLUDE);

		if (!addDefaultNestedDependencies.getOrElse(true)) {
			return new NestedJarPathProvider(nestedPaths);
		}

		NestedJarProvider baseProvider = NestedDependencyProvider.createNestedDependencyProviderFromConfiguration(getProject(), includeConfiguration);

		if (nestedPaths.isEmpty()) {
			return baseProvider;
		}

		return new MergedNestedJarProvider(
				baseProvider,
				new NestedJarPathProvider(nestedPaths)
		);
	}

	private IMappingProvider remapToSrg(LoomGradleExtension extension, IMappingProvider parent, String from, String to) throws IOException {
		TinyTree mappings = extension.shouldGenerateSrgTiny() ? extension.getMappingsProvider().getMappingsWithSrg() : extension.getMappingsProvider().getMappings();

		return sink -> {
			parent.load(new IMappingProvider.MappingAcceptor() {
				@Override
				public void acceptClass(String srcName, String dstName) {
					String srgName = mappings.getClasses()
							.stream()
							.filter(it -> Objects.equals(it.getName(from), dstName))
							.findFirst()
							.map(it -> it.getName(to))
							.orElse(dstName);
					sink.acceptClass(srcName, srgName);
				}

				@Override
				public void acceptMethod(IMappingProvider.Member method, String dstName) {
					String srgName = mappings.getClasses()
							.stream()
							.flatMap(it -> it.getMethods().stream())
							.filter(it -> Objects.equals(it.getName(from), dstName))
							.findFirst()
							.map(it -> it.getName(to))
							.orElse(dstName);
					sink.acceptMethod(method, srgName);
				}

				@Override
				public void acceptField(IMappingProvider.Member field, String dstName) {
					String srgName = mappings.getClasses()
							.stream()
							.flatMap(it -> it.getFields().stream())
							.filter(it -> Objects.equals(it.getName(from), dstName))
							.findFirst()
							.map(it -> it.getName(to))
							.orElse(dstName);
					sink.acceptField(field, srgName);
				}

				@Override
				public void acceptMethodArg(IMappingProvider.Member method, int lvIndex, String dstName) {
				}

				@Override
				public void acceptMethodVar(IMappingProvider.Member method, int lvIndex, int startOpIdx, int asmIndex, String dstName) {
				}
			});
		};
	}

	private Path[] getRemapClasspath() {
		FileCollection files = this.classpath;

		if (files == null) {
			files = getProject().getConfigurations().getByName(JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME);
		}

		return files.getFiles().stream()
				.map(File::toPath)
				.filter(Files::exists)
				.toArray(Path[]::new);
	}

	private void convertAwToAt() throws IOException {
		if (!this.atAccessWideners.isPresent()) {
			return;
		}

		Set<String> atAccessWideners = this.atAccessWideners.get();

		if (atAccessWideners.isEmpty()) {
			return;
		}

		AccessTransformSet at = AccessTransformSet.create();
		File jar = getArchiveFile().get().getAsFile();

		try (FileSystemUtil.FileSystemDelegate fileSystem = FileSystemUtil.getJarFileSystem(jar, false)) {
			FileSystem fs = fileSystem.get();
			Path atPath = fs.getPath(Constants.Forge.ACCESS_TRANSFORMER_PATH);

			if (Files.exists(atPath)) {
				throw new FileAlreadyExistsException("Jar " + jar + " already contains an access transformer - cannot convert AWs!");
			}

			for (String aw : atAccessWideners) {
				Path awPath = fs.getPath(aw);

				if (Files.notExists(awPath)) {
					throw new NoSuchFileException("Could not find AW '" + aw + "' to convert into AT!");
				}

				try (BufferedReader reader = Files.newBufferedReader(awPath, StandardCharsets.UTF_8)) {
					at.merge(Aw2At.toAccessTransformSet(reader));
				}

				Files.delete(awPath);
			}

			LoomGradleExtension extension = LoomGradleExtension.get(getProject());
			TinyTree mappings = extension.shouldGenerateSrgTiny() ? extension.getMappingsProvider().getMappingsWithSrg() : extension.getMappingsProvider().getMappings();

			try (TinyMappingsReader reader = new TinyMappingsReader(mappings, fromM.get(), toM.get())) {
				MappingSet mappingSet = reader.read();
				at = at.remap(mappingSet);
			}

			try (Writer writer = new LfWriter(Files.newBufferedWriter(atPath))) {
				AccessTransformFormats.FML.write(writer, at);
			}
		}
	}

	@InputFile
	public RegularFileProperty getInput() {
		return input;
	}

	@Input
	public Property<Boolean> getAddNestedDependencies() {
		return addNestedDependencies;
	}

	@Input
	public Property<Boolean> getAddDefaultNestedDependencies() {
		return addDefaultNestedDependencies;
	}

	@Input
	public Property<Boolean> getRemapAccessWidener() {
		return remapAccessWidener;
	}

	/**
	 * Gets the jar paths to the access wideners that will be converted to ATs for Forge runtime.
	 * If you specify multiple files, they will be merged into one.
	 *
	 * <p>The specified files will be converted and removed from the final jar.
	 *
	 * @return the property containing access widener paths in the final jar
	 */
	@Input
	public SetProperty<String> getAtAccessWideners() {
		return atAccessWideners;
	}

	public void remapOptions(Action<TinyRemapper.Builder> action) {
		this.remapOptions.add(action);
	}

	public RemapJarTask classpath(FileCollection collection) {
		if (this.classpath == null) {
			this.classpath = collection;
		} else {
			this.classpath = this.classpath.plus(collection);
		}

		return this;
	}

	@ApiStatus.Experimental // This only allows mod jars, proceed with care when trying to pass in configurations with projects, or something that depends on a task.
	public RemapJarTask include(Object... paths) {
		Collections.addAll(nestedPaths, paths);
		this.addNestedDependencies.set(true);

		return this;
	}

	@Input
	public Property<String> getFromM() {
		return fromM;
	}

	@Input
	public Property<String> getToM() {
		return toM;
	}
}
