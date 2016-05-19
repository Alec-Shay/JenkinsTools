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

package com.liferay.jenkins.results.parser;

import java.net.HttpURLConnection;
import java.net.URL;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.codec.binary.Base64;

/**
 * @author Kevin Yen
 */
public class StopBuild implements Runnable {

	private String buildURL;
	private String username;
	private String password;

	public StopBuild(String buildURL) {
		this.buildURL = buildURL;
	}

	public StopBuild(Build build) {
		this.buildURL = build.getURL();
	}

	@Override
	public void run() {
		return stopBuild(buildURL, username, password);
	}

	protected static String encodeAuthorizationFields(
		String username, String password) {

		String authorizationString = username + ":" + password;

		return new String(Base64.encodeBase64(authorizationString.getBytes()));
	}

	public static void stopBuild(
			String buildURL, String username, String password)
		throws Exception {

		URL urlObject = new URL(
			JenkinsResultsParserUtil.fixURL(
				JenkinsResultsParserUtil.getLocalURL(buildURL + "/stop")));

		HttpURLConnection httpConnection =
			(HttpURLConnection)urlObject.openConnection();

		httpConnection.setRequestMethod("POST");
		httpConnection.setRequestProperty(
			"Authorization",
			"Basic " + encodeAuthorizationFields(username, password));

		System.out.println(
			"Response from " + buildURL + "/stop: " +
				httpConnection.getResponseCode() + " " +
					httpConnection.getResponseMessage());
	}

}