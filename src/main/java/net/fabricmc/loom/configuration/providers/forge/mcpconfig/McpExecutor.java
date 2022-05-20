/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2022 FabricMC
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

package net.fabricmc.loom.configuration.providers.forge.mcpconfig;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.common.base.Stopwatch;
import com.google.common.hash.Hashing;
import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.logging.LogLevel;
import org.gradle.api.logging.Logger;
import org.gradle.process.JavaExecSpec;
import org.jetbrains.annotations.Nullable;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.configuration.providers.forge.MinecraftPatchedProvider;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.ForgeToolExecutor;
import net.fabricmc.loom.util.function.CollectionUtil;

public final class McpExecutor {
	private static final LogLevel STEP_LOG_LEVEL = LogLevel.LIFECYCLE;
	private final Project project;
	private final MinecraftPatchedProvider.MinecraftProviderBridge minecraftProvider;
	private final Path cache;
	private final List<McpConfigStep> steps;
	private final Map<String, McpConfigFunction> functions;
	private final Map<String, String> extraConfig = new HashMap<>();
	private Set<File> minecraftLibraries;

	public McpExecutor(Project project, MinecraftPatchedProvider.MinecraftProviderBridge minecraftProvider, Path cache, List<McpConfigStep> steps, Map<String, McpConfigFunction> functions) {
		this.project = project;
		this.minecraftProvider = minecraftProvider;
		this.cache = cache;
		this.steps = steps;
		this.functions = functions;
	}

	private Path getDownloadCache() throws IOException {
		Path downloadCache = cache.resolve("downloads");
		Files.createDirectories(downloadCache);
		return downloadCache;
	}

	private Path getStepCache(String step) {
		return cache.resolve(step);
	}

	private Path createStepCache(String step) throws IOException {
		Path stepCache = getStepCache(step);
		Files.createDirectories(stepCache);
		return stepCache;
	}

	private String resolve(McpConfigStep step, ConfigValue value) {
		return value.fold(ConfigValue.Constant::value, variable -> {
			String name = variable.name();
			@Nullable ConfigValue valueFromStep = step.config().get(name);

			// If the variable isn't defined in the step's config map, skip it.
			// Also skip if it would recurse with the same variable.
			if (valueFromStep != null && !valueFromStep.equals(variable)) {
				// Otherwise, resolve the nested variable.
				return resolve(step, valueFromStep);
			}

			if (name.equals(ConfigValue.SRG_MAPPINGS_NAME)) {
				return LoomGradleExtension.get(project).getSrgProvider().getSrg().toAbsolutePath().toString();
			} else if (extraConfig.containsKey(name)) {
				return extraConfig.get(name);
			}

			throw new IllegalArgumentException("Unknown MCP config variable: " + name);
		});
	}

	public Path executeUpTo(String step) throws Exception {
		extraConfig.clear();
		Stopwatch outerStopwatch = Stopwatch.createStarted();
		ExecutorService executorService = Executors.newFixedThreadPool(Math.max(1, Runtime.getRuntime().availableProcessors()));

		// Find the total number of steps we need to execute.
		int totalSteps = CollectionUtil.find(steps, s -> s.name().equals(step))
				.map(s -> steps.indexOf(s) + 1)
				.orElse(steps.size());
		int[] currentStepIndex = new int[1];
		project.getLogger().log(STEP_LOG_LEVEL, ":executing {} MCP steps", totalSteps);

		Map<String, CompletableFuture<Void>> futures = new HashMap<>();

		for (McpConfigStep currentStep : steps) {
			StepLogic stepLogic = getStepLogic(currentStep.type());
			Stopwatch stopwatch = Stopwatch.createUnstarted();

			if (stepLogic.needsMinecraftLibraries()) {
				markStepStart(currentStepIndex, totalSteps, currentStep, stepLogic, stopwatch);
				resolveMinecraftLibraries();
			}

			CompletableFuture<Void> future;

			if (stepLogic.isNoOp()) {
				executeStep(currentStepIndex, totalSteps, currentStep, stepLogic, stopwatch);
				future = CompletableFuture.completedFuture(null);
			} else {
				List<CompletableFuture<?>> stepDependencies = new ArrayList<>();

				currentStep.config().forEach((key, value) -> {
					if (value instanceof ConfigValue.Variable var && var.name().endsWith(ConfigValue.PREVIOUS_OUTPUT_SUFFIX)) {
						String name = var.name().substring(0, var.name().length() - ConfigValue.PREVIOUS_OUTPUT_SUFFIX.length());
						stepDependencies.add(futures.get(name));
					}
				});

				future = CompletableFuture.allOf(stepDependencies.toArray(CompletableFuture<?>[]::new))
						.thenRunAsync(() -> {
							try {
								executeStep(currentStepIndex, totalSteps, currentStep, stepLogic, stopwatch);
							} catch (IOException e) {
								throw new UncheckedIOException(e);
							}
						}, executorService);
			}

			futures.put(currentStep.name(), future);

			if (currentStep.name().equals(step)) {
				break;
			}
		}

		CompletableFuture.allOf(futures.values().toArray(CompletableFuture<?>[]::new)).get();
		executorService.shutdownNow();

		project.getLogger().log(STEP_LOG_LEVEL, ":MCP steps done in {}", outerStopwatch.stop());
		return Path.of(extraConfig.get(step + ConfigValue.PREVIOUS_OUTPUT_SUFFIX));
	}

	private void markStepStart(int[] currentStepIndex, int totalSteps, McpConfigStep currentStep, StepLogic stepLogic, Stopwatch stopwatch) {
		if (!stopwatch.isRunning()) {
			project.getLogger().log(STEP_LOG_LEVEL, ":step {}/{} - {}", ++currentStepIndex[0], totalSteps, stepLogic.getDisplayName(currentStep.name()));
			stopwatch.start();
		}
	}

	private void executeStep(int[] currentStepIndex, int totalSteps, McpConfigStep currentStep, StepLogic stepLogic, Stopwatch stopwatch) throws IOException {
		markStepStart(currentStepIndex, totalSteps, currentStep, stepLogic, stopwatch);
		stepLogic.execute(new ExecutionContextImpl(currentStep));
		project.getLogger().log(STEP_LOG_LEVEL, ":{} done in {}", currentStep.name(), stopwatch.stop());
	}

	private void resolveMinecraftLibraries() {
		if (minecraftLibraries == null) {
			minecraftLibraries = project.getConfigurations().getByName(Constants.Configurations.MINECRAFT_DEPENDENCIES).resolve();
		}
	}

	private StepLogic getStepLogic(String type) {
		return switch (type) {
		case "downloadManifest", "downloadJson" -> new StepLogic.NoOp();
		case "downloadClient" -> new StepLogic.NoOpWithFile(() -> minecraftProvider.getClientJar().toPath());
		case "downloadServer" -> new StepLogic.NoOpWithFile(() -> minecraftProvider.getRawServerJar().toPath());
		case "strip" -> new StepLogic.Strip();
		case "listLibraries" -> new StepLogic.ListLibraries();
		case "downloadClientMappings" -> new StepLogic.DownloadManifestFile(minecraftProvider.getVersionInfo().download("client_mappings"));
		case "downloadServerMappings" -> new StepLogic.DownloadManifestFile(minecraftProvider.getVersionInfo().download("server_mappings"));
		default -> {
			if (functions.containsKey(type)) {
				yield new StepLogic.OfFunction(functions.get(type));
			}

			throw new UnsupportedOperationException("MCP config step type: " + type);
		}
		};
	}

	private class ExecutionContextImpl implements StepLogic.ExecutionContext {
		private final McpConfigStep step;
		private Path output;

		ExecutionContextImpl(McpConfigStep step) {
			this.step = step;
		}

		@Override
		public Logger logger() {
			return project.getLogger();
		}

		@Override
		public Path setOutput(String fileName) throws IOException {
			createStepCache(step.name());
			return setOutput(getStepCache(step.name()).resolve(fileName));
		}

		@Override
		public Path setOutput(Path output) {
			this.output = output;
			String absolutePath = output.toAbsolutePath().toString();
			extraConfig.put(step.name() + ConfigValue.PREVIOUS_OUTPUT_SUFFIX, absolutePath);
			return output;
		}

		@Override
		public Path mappings() {
			return LoomGradleExtension.get(project).getMcpConfigProvider().getMappings();
		}

		@Override
		public String resolve(ConfigValue value) {
			if (value instanceof ConfigValue.Variable var && var.name().equals(ConfigValue.OUTPUT)) {
				return output.toAbsolutePath().toString();
			}

			return McpExecutor.this.resolve(step, value);
		}

		@Override
		public Path download(String url) throws IOException {
			Path path = getDownloadCache().resolve(Hashing.sha256().hashString(url, StandardCharsets.UTF_8).toString().substring(0, 24));
			redirectAwareDownload(url, path);
			return path;
		}

		// Some of these files linked to the old Forge maven, let's follow the redirects to the new one.
		private static void redirectAwareDownload(String urlString, Path path) throws IOException {
			URL url = new URL(urlString);

			if (url.getProtocol().equals("http")) {
				url = new URL("https", url.getHost(), url.getPort(), url.getFile());
			}

			HttpURLConnection connection = (HttpURLConnection) url.openConnection();
			connection.connect();

			if (connection.getResponseCode() == HttpURLConnection.HTTP_MOVED_PERM || connection.getResponseCode() == HttpURLConnection.HTTP_MOVED_TEMP) {
				redirectAwareDownload(connection.getHeaderField("Location"), path);
			} else {
				try (InputStream in = connection.getInputStream()) {
					Files.copy(in, path);
				}
			}
		}

		@Override
		public void javaexec(Action<? super JavaExecSpec> configurator) {
			ForgeToolExecutor.exec(project, configurator).rethrowFailure().assertNormalExitValue();
		}

		@Override
		public Set<File> getMinecraftLibraries() {
			return minecraftLibraries;
		}
	}
}
