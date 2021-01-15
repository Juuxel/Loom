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

package net.fabricmc.loom.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Collectors;

public class ThreadingUtils {
	public static <T> void run(Collection<T> values, UnsafeConsumer<T> action) {
		run(values.stream()
				.<UnsafeRunnable>map(t -> () -> action.accept(t))
				.collect(Collectors.toList()));
	}

	public static void run(UnsafeRunnable... jobs) {
		run(Arrays.asList(jobs));
	}

	public static void run(Collection<UnsafeRunnable> jobs) {
		try {
			ExecutorService service = Executors.newFixedThreadPool(Math.min(jobs.size(), Runtime.getRuntime().availableProcessors() / 2));
			List<Future<?>> futures = new LinkedList<>();

			for (UnsafeRunnable runnable : jobs) {
				futures.add(service.submit(() -> {
					try {
						runnable.run();
					} catch (Throwable throwable) {
						throw new RuntimeException(throwable);
					}
				}));
			}

			for (Future<?> future : futures) {
				future.get();
			}

			service.shutdownNow();
		} catch (InterruptedException | ExecutionException e) {
			throw new RuntimeException(e);
		}
	}

	public static <T, R> List<R> get(Collection<T> values, Function<T, R> action) {
		return get(values.stream()
				.<UnsafeCallable<R>>map(t -> () -> action.apply(t))
				.collect(Collectors.toList()));
	}

	@SafeVarargs
	public static <T> List<T> get(UnsafeCallable<T>... jobs) {
		return get(Arrays.asList(jobs));
	}

	public static <T> List<T> get(Collection<UnsafeCallable<T>> jobs) {
		try {
			ExecutorService service = Executors.newFixedThreadPool(Math.min(jobs.size(), Runtime.getRuntime().availableProcessors() / 2));
			List<Future<T>> futures = new LinkedList<>();

			for (UnsafeCallable<T> runnable : jobs) {
				futures.add(service.submit(() -> {
					try {
						return runnable.call();
					} catch (Throwable throwable) {
						throw new RuntimeException(throwable);
					}
				}));
			}

			List<T> result = new ArrayList<>();

			for (Future<T> future : futures) {
				result.add(future.get());
			}

			service.shutdownNow();
			return result;
		} catch (InterruptedException | ExecutionException e) {
			throw new RuntimeException(e);
		}
	}

	public interface UnsafeRunnable {
		void run() throws Throwable;
	}

	public interface UnsafeCallable<T> {
		T call() throws Throwable;
	}

	public interface UnsafeConsumer<T> {
		void accept(T value) throws Throwable;
	}
}
