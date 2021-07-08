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

package net.fabricmc.loom.build;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

import com.google.common.io.Files;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.FileCollectionDependency;
import org.gradle.api.artifacts.ModuleDependency;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.artifacts.query.ArtifactResolutionQuery;
import org.gradle.api.artifacts.result.ArtifactResult;
import org.gradle.api.artifacts.result.ComponentArtifactsResult;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;
import org.gradle.api.file.FileCollection;
import org.gradle.api.logging.Logger;
import org.gradle.jvm.JvmLibrary;
import org.gradle.language.base.artifact.SourcesArtifact;
import org.jetbrains.annotations.Nullable;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.LoomGradlePlugin;
import net.fabricmc.loom.configuration.LoomProjectData;
import net.fabricmc.loom.configuration.RemappedConfigurationEntry;
import net.fabricmc.loom.configuration.mods.ModProcessor;
import net.fabricmc.loom.configuration.processors.dependency.ModDependencyInfo;
import net.fabricmc.loom.configuration.processors.dependency.RemapData;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.OperatingSystem;
import net.fabricmc.loom.util.SourceRemapper;

@SuppressWarnings("UnstableApiUsage")
public class ModCompileRemapper {
	// These are placeholders that are used when the actual group/version is missing (null or empty).
	// This happens when the dependency is a FileCollectionDependency or from a flatDir repository.
	private static final String MISSING_GROUP = "unspecified";
	private static final String MISSING_VERSION = "unspecified";

	private static String replaceGroupIfMissing(@Nullable String group) {
		return group == null || group.isEmpty() ? MISSING_GROUP : group;
	}

	private static String replaceVersionIfMissing(@Nullable String version) {
		return version == null || version.isEmpty() ? MISSING_VERSION : version;
	}

	public static void remapDependencies(Project project, String mappingsSuffix, LoomGradleExtension extension, SourceRemapper sourceRemapper) {
		Logger logger = project.getLogger();
		DependencyHandler dependencies = project.getDependencies();
		boolean refreshDeps = LoomGradlePlugin.refreshDeps;

		final File modStore = extension.getRemappedModCache();
		final RemapData remapData = new RemapData(mappingsSuffix, modStore);

		final LoomProjectData data = extension.getProjectData();

		for (RemappedConfigurationEntry entry : Constants.MOD_COMPILE_ENTRIES) {
			data.getLazyConfigurationProvider(entry.getRemappedConfiguration()).configure(remappedConfig -> {
				Configuration sourceConfig = project.getConfigurations().getByName(entry.sourceConfiguration());
				Configuration regularConfig = project.getConfigurations().getByName(entry.getTargetConfiguration(project.getConfigurations()));

				List<ModDependencyInfo> modDependencies = new ArrayList<>();

				for (ResolvedArtifact artifact : sourceConfig.getResolvedConfiguration().getResolvedArtifacts()) {
					String group = replaceGroupIfMissing(artifact.getModuleVersion().getId().getGroup());
					String name = artifact.getModuleVersion().getId().getName();
					String version = replaceVersionIfMissing(artifact.getModuleVersion().getId().getVersion());

					if (!isFabricMod(logger, artifact.getFile(), artifact.getId())) {
						addToRegularCompile(project, regularConfig, artifact);
						continue;
					}

					ModDependencyInfo info = new ModDependencyInfo(group, name, version, artifact.getClassifier(), artifact.getFile(), remappedConfig, remapData);
					modDependencies.add(info);

					File remappedSources = info.getRemappedOutput("sources");

					if ((!remappedSources.exists() || refreshDeps) && !OperatingSystem.isCIBuild()) {
						File sources = findSources(dependencies, artifact);

						if (sources != null) {
							scheduleSourcesRemapping(project, sourceRemapper, sources, info.getRemappedNotation(), remappedSources);
						}
					}
				}

				for (FileCollectionDependency dependency : sourceConfig.getAllDependencies().withType(FileCollectionDependency.class)) {
					String group = replaceGroupIfMissing(dependency.getGroup());
					String version = replaceVersionIfMissing(dependency.getVersion());
					FileCollection files = dependency.getFiles();

					// Create a mod dependency for each file in the file collection
					for (File artifact : files) {
						if (!isFabricMod(logger, artifact, artifact.getName())) {
							dependencies.add(regularConfig.getName(), project.files(artifact));
							continue;
						}

						String name = Files.getNameWithoutExtension(artifact.getAbsolutePath());
						ModDependencyInfo info = new ModDependencyInfo(group, name, version, null, artifact, remappedConfig, remapData);
						modDependencies.add(info);
					}
				}

				for (ModDependencyInfo info : modDependencies) {
					if (refreshDeps) {
						info.forceRemap();
					}

					String remappedLog = info.getRemappedNotation() + " (" + mappingsSuffix + ")";
					project.getLogger().info(":providing " + remappedLog);
				}

				try {
					ModProcessor.processMods(project, modDependencies);
				} catch (IOException e) {
					// Failed to remap, lets clean up to ensure we try again next time
					modDependencies.forEach(info -> info.getRemappedOutput().delete());
					throw new RuntimeException("Failed to remap mods", e);
				}

				// Add all of the remapped mods onto the config
				for (ModDependencyInfo info : modDependencies) {
					project.getDependencies().add(info.targetConfig.getName(), info.getRemappedNotation());
				}
			});
		}
	}

	/**
	 * Checks if an artifact is a fabric mod, according to the presence of a fabric.mod.json.
	 */
	private static boolean isFabricMod(Logger logger, File artifact, Object id) {
		try (ZipFile zipFile = new ZipFile(artifact)) {
			if (zipFile.getEntry("fabric.mod.json") != null) {
				logger.info("Found Fabric mod in modCompile: {}", id);
				return true;
			}

			return false;
		} catch (IOException e) {
			return false;
		}
	}

	private static void addToRegularCompile(Project project, Configuration regularCompile, ResolvedArtifact artifact) {
		project.getLogger().info(":providing " + artifact);
		DependencyHandler dependencies = project.getDependencies();
		Dependency dep = dependencies.module(artifact.getModuleVersion().toString()
						+ (artifact.getClassifier() == null ? "" : ':' + artifact.getClassifier())); // the owning module of the artifact

		if (dep instanceof ModuleDependency moduleDependency) {
			moduleDependency.setTransitive(false);
		}

		dependencies.add(regularCompile.getName(), dep);
	}

	public static File findSources(DependencyHandler dependencies, ResolvedArtifact artifact) {
		@SuppressWarnings("unchecked") ArtifactResolutionQuery query = dependencies.createArtifactResolutionQuery()
				.forComponents(artifact.getId().getComponentIdentifier())
				.withArtifacts(JvmLibrary.class, SourcesArtifact.class);

		for (ComponentArtifactsResult result : query.execute().getResolvedComponents()) {
			for (ArtifactResult srcArtifact : result.getArtifacts(SourcesArtifact.class)) {
				if (srcArtifact instanceof ResolvedArtifactResult) {
					return ((ResolvedArtifactResult) srcArtifact).getFile();
				}
			}
		}

		return null;
	}

	private static void scheduleSourcesRemapping(Project project, SourceRemapper sourceRemapper, File sources, String remappedLog, File remappedSources) {
		project.getLogger().debug(":providing " + remappedLog + " sources");

		if (!remappedSources.exists() || sources.lastModified() <= 0 || sources.lastModified() > remappedSources.lastModified() || LoomGradlePlugin.refreshDeps) {
			sourceRemapper.scheduleRemapSources(sources, remappedSources, false, true); // Depenedency sources are used in ide only so don't need to be reproducable
		} else {
			project.getLogger().info(remappedSources.getName() + " is up to date with " + sources.getName());
		}
	}
}
