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
import java.io.File;

import java.net.URL;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.Option;

import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;

/**
 * @author Kevin Yen
 */
public class JenkinsStatus {

	private static final Logger logger = (Logger) LoggerFactory.getLogger(
		JenkinsStatus.class);

	private static final int THREAD_POOL_SIZE = 120;

	private static final int REQUEST_TIMEOUT = 30 * 1000;

	private File serversListFile = new File("servers.list");

	private JsonGetter jsonGetter = new LocalJsonGetter(REQUEST_TIMEOUT);

	private boolean showBuildInfo = false;

	private List<BuildMatcher> buildMatchers = new ArrayList<>();

	private Pattern pattern = Pattern.compile(".*");

	private void printMessage(String tag, String value, int maxTagLength) {
		StringBuilder sb = new StringBuilder();

		sb.append("  ");
		sb.append(tag);
		sb.append(':');

		int padding = maxTagLength - tag.length();

		for (int i = 0; i < padding; i++) {
			sb.append(' ');
		}

		sb.append(value);

		System.out.println(sb.toString());
	}

	private void printMessage(String tag, String[] values) {
		StringBuilder sb = new StringBuilder();

		sb.append("  ");
		sb.append(tag);
		sb.append(':');
		sb.append('\n');

		for (String value : values) {
			sb.append("    ");
			sb.append(value);
			sb.append('\n');
		}

		System.out.println(sb.toString());
	}

	private void printMessage(String tag, Map<String, String> values) {
		StringBuilder sb = new StringBuilder();

		sb.append("  ");
		sb.append(tag);
		sb.append(':');
		sb.append('\n');

		for (String name : values.keySet()) {
			sb.append("    ");
			sb.append(name);
			sb.append('=');
			sb.append(values.get(name));
			sb.append('\n');
		}

		System.out.println(sb.toString());
	}

	private void processArgs(String[] args) throws Exception {
		CommandLineParser parser = new DefaultParser();

		Options options = new Options();

		options.addOption("i", "info", false, "Set logging level to info");
		options.addOption("d", "debug", false, "Set logging level to debug");
		options.addOption(
			"u", "user", true, "Specify the username used in authentication");
		options.addOption(
			"n", "name", true, "Set the regular expression to match job name");
		options.addOption(
			"r", "result", true, "Specify the result of the matching build");
		options.addOption(
			"c", "building", true,
				"Whether the build is building: true, false, any");
		options.addOption(
			"f", "file", true, "File containing jenkins servers list");
		options.addOption(
			Option.builder("p")
			.longOpt("parameters")
			.hasArgs()
			.desc("Specify the parameter of the build to match")
			.valueSeparator(',')
			.build());
		options.addOption(
			Option.builder("b")
			.longOpt("before")
			.hasArgs()
			.desc("Match builds before specified time")
			.build());
		options.addOption(
			Option.builder("a")
			.longOpt("after")
			.hasArgs()
			.desc("Match builds after specified time")
			.build());

		CommandLine line = parser.parse(options, args);

		if (line.hasOption("i")) {
			Logger rootLogger = (Logger) LoggerFactory.getLogger(
				Logger.ROOT_LOGGER_NAME);
			rootLogger.setLevel(Level.INFO);

			showBuildInfo = true;
		}

		if (line.hasOption("d")) {
			Logger rootLogger = (Logger) LoggerFactory.getLogger(
				Logger.ROOT_LOGGER_NAME);
			rootLogger.setLevel(Level.DEBUG);

			logger.debug(
				"Checking for the following options: {}", options);

			showBuildInfo = true;
		}

		if (line.hasOption("f")) {
			logger.debug("Loading file {}", line.getOptionValue("f"));

			serversListFile = new File(line.getOptionValue("f"));
		}

		if (line.hasOption("n")) {
			logger.debug(
				"Using the regular expression pattern {} to match job name",
					line.getOptionValue("n"));

			pattern = Pattern.compile(line.getOptionValue("n"));
		}

		if (line.hasOption("u")) {
			Console console = System.console();

			if (console == null) {
				throw new IllegalStateException(
					"Unable to get Console instance");
			}

			String username = line.getOptionValue("u");
			String password = new String(
				console.readPassword("Enter password for " + username + " :"));

			jsonGetter = new RemoteJsonGetter(
				username, password, REQUEST_TIMEOUT);
		}

		if (line.hasOption("c")) {
			buildMatchers.add(new BuildingMatcher(line.getOptionValue("c")));
		}
		else {
			buildMatchers.add(new BuildingMatcher("TRUE"));
		}

		if (line.hasOption("p")) {
			buildMatchers.add(new ParametersMatcher(line.getOptionValues("p")));
		}

		if (line.hasOption("r")) {
			buildMatchers.add(new ResultMatcher(line.getOptionValue("r")));
		}

		if (line.hasOption("b")) {
			buildMatchers.add(
				new TimestampMatcher(true, line.getOptionValue("b")));
		}

		if (line.hasOption("a")) {
			buildMatchers.add(
				new TimestampMatcher(false, line.getOptionValue("a")));
		}
	}

	public void listBuilds() throws Exception {
		Set<String> jenkinsURLs = new HashSet<>();

		logger.debug("Loading Jenkins URLs from {}", serversListFile);

		for (URL jenkinsURL : JenkinsURLs.getJenkinsURLs(serversListFile)) {
			String jenkinsURLString = jsonGetter.convertURL(
				jenkinsURL.toString());

			jenkinsURLs.add(jenkinsURLString);

			logger.debug(
				"Adding {} to the list of servers to search",
					jenkinsURLString);
		}

		ExecutorService executor = Executors.newFixedThreadPool(
			THREAD_POOL_SIZE);

		Set<Build> matchingBuilds = new HashSet<>();

		try {
			Set<Job> jobs = JobsGetter.getJobs(
				jsonGetter, executor, jenkinsURLs);

			Set<Job> matchingJobs = new HashSet<>();

			for (Job job : jobs) {
				Matcher matcher = pattern.matcher(job.getName());

				if (matcher.find()) {
					matchingJobs.add(job);

					logger.debug(
						"Found job {} matching regular expression",
							job.getName());
				}
			}

			logger.info(
				"Found {} jobs matching regular expression '{}'",
					matchingJobs.size(), pattern);

			Set<Build> builds = BuildsGetter.getBuilds(
				jsonGetter, executor, matchingJobs);

			for (Build build : builds) {
				boolean match = true;

				for (BuildMatcher buildMatcher : buildMatchers) {
					if (!buildMatcher.matches(build)) {
						match = false;
					}
				}

				if (match) {
					matchingBuilds.add(build);
				}
			}
		}
		catch (ExecutionException e) {
			e.printStackTrace();

			throw e;
		}
		finally {
			System.out.println("Found " + matchingBuilds.size() + " builds");

			for (Build build : matchingBuilds){
				if (showBuildInfo) {
					String date = new Date(build.getTimestamp()).toString();
					String number = new Integer(build.getNumber()).toString();
					String building = new Boolean(
						build.isBuilding()).toString();

					String duration = "";

					if (build.getDuration() > 0) {
						duration = new Long(build.getDuration()).toString();
					}

					System.out.print("\n");
					System.out.println(build.getURL());

					printMessage("Name", build.getJob().getName(), 12);
					printMessage("Number", number, 12);
					printMessage("Time", date, 12);
					printMessage("Building", building, 12);
					printMessage("Duration", duration, 12);
					printMessage("Result", build.getResult(), 12);
					printMessage("Parameters", build.getParameters());
				}
				else {
					System.out.println(build.getURL());
				}

			}

			executor.shutdown();
		}
	}

	public static void main(String [] args) throws Exception {
		JenkinsStatus jenkinsStatus = new JenkinsStatus();

		jenkinsStatus.processArgs(args);

		jenkinsStatus.listBuilds();
	}

}