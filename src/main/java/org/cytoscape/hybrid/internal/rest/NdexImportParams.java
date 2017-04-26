package org.cytoscape.hybrid.internal.rest;

public class NdexImportParams {

	private String uuid;
	private String userId;
	private String password;
	private String serverUrl;

	public NdexImportParams(String uuid, String userId, String password, String serverUrl) {
		this.uuid = uuid;
		this.serverUrl = serverUrl;
		this.password = password;
		this.userId = userId;
	}

	public String getUuid() {
		return uuid;
	}

	public void setUuid(String uuid) {
		this.uuid = uuid;
	}

	public String getUserId() {
		return userId;
	}

	public void setUserId(String userId) {
		this.userId = userId;
	}

	public String getPassword() {
		return password;
	}

	public void setToken(final String password) {
		this.password = password;
	}

	public String getServerUrl() {
		return serverUrl;
	}

	public void setServerUrl(String serverUrl) {
		this.serverUrl = serverUrl;
	}
}
