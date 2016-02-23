/**
 * Copyright (c) 2000-present Liferay, Inc. All rights reserved.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 */

package com.liferay.jenkins.tools;

import java.io.Console;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;

/**
 * @author Kevin Yen
 */
public class JenkinsStatus {

	public static void main(String [] args) throws Exception {
		CommandLineParser parser = new DefaultParser();

		Options options = new Options();

		options.addOption("u", "user", true, "Specify the username used in authentication");

		CommandLine line = parser.parse(options, args);

		JsonGetter jsonGetter = new LocalJsonGetter();

		boolean remote = false;

		if (line.hasOption("u")) {
			Console console = System.console();

			if (console == null) {
				System.out.println("Unable to get Console instance");
				System.exit(0);
			}

			String password = new String(console.readPassword("Enter host password: "));

			String username = line.getOptionValue("u");

			jsonGetter = new RemoteJsonGetter(username, password);

			remote = true;
		}

		printActivePullRequests(jsonGetter, remote);
	}

	public static void printActivePullRequests(JsonGetter jsonGetter, boolean remote) throws Exception {
		Set<String> pullRequestJobTypes = new HashSet<>();

		pullRequestJobTypes.add("test-portal-acceptance-pullrequest");
		pullRequestJobTypes.add("test-plugins-acceptance-pullrequest");

		Set<String> pullRequestJobBranches = new HashSet<>();

		pullRequestJobBranches.add("master");
		pullRequestJobBranches.add("ee-7.0.x");
		pullRequestJobBranches.add("ee-6.2.x");
		pullRequestJobBranches.add("ee-6.1.x");

		Set<String> pullRequestJobURLs = JenkinsJobURLs.getJenkinsJobURLs(1, 20, pullRequestJobTypes, pullRequestJobBranches, remote);

		pullRequestJobURLs.addAll(JenkinsJobURLs.getJenkinsJobURLs(1, 20, "test-jenkins-acceptance-pullrequest", remote));

		Set<Future<List<String>>> futures = new HashSet<Future<List<String>>>();

		int threadPoolSize = 120;

		ExecutorService executor = Executors.newFixedThreadPool(threadPoolSize);

		CompletionService<List<String>> completionService = new ExecutorCompletionService<List<String>>(executor);

		System.out.println("Checking " + pullRequestJobURLs.size() + " URLs using with a thead pool size of " + threadPoolSize);

		for (String pullRequestJobURL : pullRequestJobURLs) {
			Callable<List<String>> callable = new ActiveBuildURLsGetter(jsonGetter, pullRequestJobURL);

			futures.add(completionService.submit(callable));
		}

		List<String> activePullRequestURLs = new ArrayList<>();

		while (futures.size() > 0) {
			Future<List<String>> completedFuture = completionService.take();

			futures.remove(completedFuture);

			List<String> activeBuildURLs = completedFuture.get();

			activePullRequestURLs.addAll(activeBuildURLs);
		}

		executor.shutdown();

		System.out.println("Listing currently running pull requests...");

		for (String activePullRequestURL : activePullRequestURLs) {
			System.out.println(activePullRequestURL);
		}

		System.out.println(activePullRequestURLs.size() + " pull requests are currently running");
	}

}