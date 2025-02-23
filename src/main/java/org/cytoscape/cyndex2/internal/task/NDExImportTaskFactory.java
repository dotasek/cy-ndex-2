package org.cytoscape.cyndex2.internal.task;

import java.io.IOException;
import java.util.UUID;

import javax.ws.rs.core.Response.Status;

import org.cytoscape.cyndex2.internal.CyServiceModule;
import org.cytoscape.cyndex2.internal.rest.errors.ErrorBuilder;
import org.cytoscape.cyndex2.internal.rest.errors.ErrorType;
import org.cytoscape.cyndex2.internal.rest.parameter.NDExImportParameters;
import org.cytoscape.work.AbstractTaskFactory;
import org.cytoscape.work.TaskIterator;
import org.ndexbio.model.exceptions.NdexException;

public class NDExImportTaskFactory extends AbstractTaskFactory {

	private ErrorBuilder errorBuilder;
	private NDExImportParameters params;
	
	private NetworkImportTask importer;

	public NDExImportTaskFactory(NDExImportParameters params) {
		super();
		this.params = params;
		this.errorBuilder = CyServiceModule.INSTANCE.getErrorBuilder();
	}
	
	private NetworkImportTask buildImportTask() throws IOException, NdexException {
		UUID uuid = validateImportParameters(params);
		
		if (params.username != null && params.password != null) {
			return new NetworkImportTask(params.username, params.password, params.serverUrl, uuid,
					params.accessKey);
		}
		return new NetworkImportTask(params.serverUrl, uuid, params.accessKey, params.idToken);
	}
	
	@Override
	public TaskIterator createTaskIterator() {		
		try {
			importer = buildImportTask();
			return new TaskIterator(importer);
		} catch (IOException | NdexException e) {
			final String message = "Failed to connect to server and retrieve network. " + e.getMessage();
			throw errorBuilder.buildException(Status.INTERNAL_SERVER_ERROR, message, ErrorType.INTERNAL);		
		}
	}
	
	private UUID validateImportParameters(NDExImportParameters params) {
		if (params == null) {
			final String message = "No import parameters found.";
			throw errorBuilder.buildException(Status.BAD_REQUEST, message, ErrorType.INVALID_PARAMETERS);
		}
		if (params.serverUrl == null) {
			params.serverUrl = "http://ndexbio.org/v2";
		}
		if (params.uuid == null) {
			final String message = "Must provide a uuid to import a network";
			throw errorBuilder.buildException(Status.BAD_REQUEST, message, ErrorType.INVALID_PARAMETERS);
		}
		try {
			return UUID.fromString(params.uuid);
		}catch (IllegalArgumentException e) {
			String message = "Invalid UUID parameter: " + params.uuid + ". Must conform to UUID standards";
			throw errorBuilder.buildException(Status.BAD_REQUEST, message, ErrorType.INVALID_PARAMETERS);
		}
	}
	
	public long getSUID() {
		return importer.getSUID();
	}

}
