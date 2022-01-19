/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2021 FabricMC
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

package net.fabricmc.loom.extension;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import com.google.common.base.Suppliers;
import org.cadixdev.lorenz.MappingSet;
import org.cadixdev.mercury.Mercury;
import org.gradle.api.Action;
import org.gradle.api.NamedDomainObjectProvider;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileCollection;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.api.ForgeExtensionAPI;
import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.configuration.InstallerData;
import net.fabricmc.loom.configuration.LoomDependencyManager;
import net.fabricmc.loom.configuration.accesswidener.AccessWidenerFile;
import net.fabricmc.loom.configuration.processors.JarProcessorManager;
import net.fabricmc.loom.configuration.providers.forge.DependencyProviders;
import net.fabricmc.loom.configuration.providers.mappings.MappingsProviderImpl;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftProvider;
import net.fabricmc.loom.configuration.providers.minecraft.mapped.IntermediaryMinecraftProvider;
import net.fabricmc.loom.configuration.providers.minecraft.mapped.NamedMinecraftProvider;
import net.fabricmc.loom.configuration.providers.minecraft.mapped.SrgMinecraftProvider;
import net.fabricmc.loom.util.ModPlatform;
import net.fabricmc.loom.util.function.LazyBool;

public class LoomGradleExtensionImpl extends LoomGradleExtensionApiImpl implements LoomGradleExtension {
	private final Project project;
	private final MixinExtension mixinApExtension;
	private final LoomFiles loomFiles;
	private final ConfigurableFileCollection unmappedMods;
	private final Supplier<ForgeExtensionAPI> forgeExtension;

	private final MappingSet[] srcMappingCache = new MappingSet[2];
	private final Mercury[] srcMercuryCache = new Mercury[2];
	private final Map<String, NamedDomainObjectProvider<Configuration>> lazyConfigurations = new HashMap<>();
	private final List<AccessWidenerFile> transitiveAccessWideners = new ArrayList<>();

	private LoomDependencyManager dependencyManager;
	private JarProcessorManager jarProcessorManager;
	private MinecraftProvider minecraftProvider;
	private MappingsProviderImpl mappingsProvider;
	private NamedMinecraftProvider<?> namedMinecraftProvider;
	private IntermediaryMinecraftProvider<?> intermediaryMinecraftProvider;
	private SrgMinecraftProvider<?> srgMinecraftProvider;
	private InstallerData installerData;

	// +-------------------+
	// | Architectury Loom |
	// +-------------------+
	private static final String INCLUDE_PROPERTY = "loom.forge.include";
	private final LazyBool supportsInclude;
	private DependencyProviders dependencyProviders;

	public LoomGradleExtensionImpl(Project project, LoomFiles files) {
		super(project, files);
		this.project = project;
		// Initiate with newInstance to allow gradle to decorate our extension
		this.mixinApExtension = project.getObjects().newInstance(MixinExtensionImpl.class, project);
		this.loomFiles = files;
		this.unmappedMods = project.files();
		this.forgeExtension = Suppliers.memoize(() -> isForge() ? project.getObjects().newInstance(ForgeExtensionImpl.class, project, this) : null);
		this.supportsInclude = new LazyBool(() -> Boolean.parseBoolean(Objects.toString(project.findProperty(INCLUDE_PROPERTY))));
	}

	@Override
	protected Project getProject() {
		return project;
	}

	@Override
	public LoomFiles getFiles() {
		return loomFiles;
	}

	@Override
	public void setDependencyManager(LoomDependencyManager dependencyManager) {
		this.dependencyManager = dependencyManager;
	}

	@Override
	public LoomDependencyManager getDependencyManager() {
		return Objects.requireNonNull(dependencyManager, "Cannot get LoomDependencyManager before it has been setup");
	}

	@Override
	public void setJarProcessorManager(JarProcessorManager jarProcessorManager) {
		this.jarProcessorManager = jarProcessorManager;
	}

	@Override
	public JarProcessorManager getJarProcessorManager() {
		return Objects.requireNonNull(jarProcessorManager, "Cannot get JarProcessorManager before it has been setup");
	}

	@Override
	public MinecraftProvider getMinecraftProvider() {
		return Objects.requireNonNull(minecraftProvider, "Cannot get MinecraftProvider before it has been setup");
	}

	@Override
	public void setMinecraftProvider(MinecraftProvider minecraftProvider) {
		this.minecraftProvider = minecraftProvider;
	}

	@Override
	public MappingsProviderImpl getMappingsProvider() {
		return Objects.requireNonNull(mappingsProvider, "Cannot get MappingsProvider before it has been setup");
	}

	@Override
	public void setMappingsProvider(MappingsProviderImpl mappingsProvider) {
		this.mappingsProvider = mappingsProvider;
	}

	@Override
	public NamedMinecraftProvider<?> getNamedMinecraftProvider() {
		return Objects.requireNonNull(namedMinecraftProvider, "Cannot get NamedMinecraftProvider before it has been setup");
	}

	@Override
	public IntermediaryMinecraftProvider<?> getIntermediaryMinecraftProvider() {
		return Objects.requireNonNull(intermediaryMinecraftProvider, "Cannot get IntermediaryMinecraftProvider before it has been setup");
	}

	@Override
	public void setNamedMinecraftProvider(NamedMinecraftProvider<?> namedMinecraftProvider) {
		this.namedMinecraftProvider = namedMinecraftProvider;
	}

	@Override
	public void setIntermediaryMinecraftProvider(IntermediaryMinecraftProvider<?> intermediaryMinecraftProvider) {
		this.intermediaryMinecraftProvider = intermediaryMinecraftProvider;
	}

	@Override
	public SrgMinecraftProvider<?> getSrgMinecraftProvider() {
		return Objects.requireNonNull(srgMinecraftProvider, "Cannot get SrgMinecraftProvider before it has been setup");
	}

	@Override
	public void setSrgMinecraftProvider(SrgMinecraftProvider<?> srgMinecraftProvider) {
		this.srgMinecraftProvider = srgMinecraftProvider;
	}

	@Override
	public FileCollection getMinecraftJarsCollection(MappingsNamespace mappingsNamespace) {
		return getProject().files(
			getProject().provider(() ->
				getProject().files(getMinecraftJars(mappingsNamespace).stream().map(Path::toFile).toList())
			)
		);
	}

	@Override
	public MappingSet getOrCreateSrcMappingCache(int id, Supplier<MappingSet> factory) {
		if (id < 0 || id >= srcMappingCache.length) return factory.get();
		return srcMappingCache[id] != null ? srcMappingCache[id] : (srcMappingCache[id] = factory.get());
	}

	@Override
	public Mercury getOrCreateSrcMercuryCache(int id, Supplier<Mercury> factory) {
		if (id < 0 || id >= srcMercuryCache.length) return factory.get();
		return srcMercuryCache[id] != null ? srcMercuryCache[id] : (srcMercuryCache[id] = factory.get());
	}

	@Override
	public ConfigurableFileCollection getUnmappedModCollection() {
		return unmappedMods;
	}

	public void setInstallerData(InstallerData object) {
		this.installerData = object;
	}

	@Override
	public InstallerData getInstallerData() {
		return installerData;
	}

	@Override
	public boolean isRootProject() {
		return project.getRootProject() == project;
	}

	@Override
	public NamedDomainObjectProvider<Configuration> createLazyConfiguration(String name, Action<? super Configuration> configurationAction) {
		NamedDomainObjectProvider<Configuration> provider = project.getConfigurations().register(name, configurationAction);

		if (lazyConfigurations.containsKey(name)) {
			throw new IllegalStateException("Duplicate configuration name" + name);
		}

		lazyConfigurations.put(name, provider);

		return provider;
	}

	@Override
	public NamedDomainObjectProvider<Configuration> getLazyConfigurationProvider(String name) {
		NamedDomainObjectProvider<Configuration> provider = lazyConfigurations.get(name);

		if (provider == null) {
			throw new NullPointerException("Could not find provider with name: " + name);
		}

		return provider;
	}

	@Override
	public MixinExtension getMixin() {
		return this.mixinApExtension;
	}

	@Override
	public List<AccessWidenerFile> getTransitiveAccessWideners() {
		return transitiveAccessWideners;
	}

	@Override
	public void addTransitiveAccessWideners(List<AccessWidenerFile> accessWidenerFiles) {
		transitiveAccessWideners.addAll(accessWidenerFiles);
	}

	@Override
	protected String getMinecraftVersion() {
		return getMinecraftProvider().minecraftVersion();
	}

	@Override
	public ForgeExtensionAPI getForge() {
		ModPlatform.assertPlatform(this, ModPlatform.FORGE);
		return forgeExtension.get();
	}

	@Override
	public boolean supportsInclude() {
		return !isForge() || supportsInclude.getAsBoolean();
	}

	@Override
	public DependencyProviders getDependencyProviders() {
		return dependencyProviders;
	}

	@Override
	public void setDependencyProviders(DependencyProviders dependencyProviders) {
		this.dependencyProviders = dependencyProviders;
	}
}
