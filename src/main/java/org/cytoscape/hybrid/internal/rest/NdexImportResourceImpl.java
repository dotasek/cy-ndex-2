package org.cytoscape.hybrid.internal.rest;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.ws.rs.BadRequestException;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import org.cytoscape.application.CyApplicationManager;
import org.cytoscape.ci.CIErrorFactory;
import org.cytoscape.ci.CIExceptionFactory;
import org.cytoscape.ci.CIWrapping;
import org.cytoscape.ci.model.CIError;
import org.cytoscape.hybrid.internal.CxTaskFactoryManager;
import org.cytoscape.hybrid.internal.rest.errors.ErrorBuilder;
import org.cytoscape.hybrid.internal.rest.reader.CxReaderFactory;
import org.cytoscape.hybrid.internal.rest.reader.UpdateTableTask;
import org.cytoscape.io.read.CyNetworkReader;
import org.cytoscape.io.read.InputStreamTaskFactory;
import org.cytoscape.io.write.CyNetworkViewWriterFactory;
import org.cytoscape.io.write.CyWriter;
import org.cytoscape.model.CyColumn;
import org.cytoscape.model.CyNetwork;
import org.cytoscape.model.CyNetworkManager;
import org.cytoscape.model.CyRow;
import org.cytoscape.model.CyTable;
import org.cytoscape.model.subnetwork.CyRootNetwork;
import org.cytoscape.model.subnetwork.CySubNetwork;
import org.cytoscape.work.Task;
import org.cytoscape.work.TaskFactory;
import org.cytoscape.work.TaskIterator;
import org.cytoscape.work.TaskMonitor;
import org.ndexbio.rest.client.NdexRestClient;
import org.ndexbio.rest.client.NdexRestClientModelAccessLayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

public class NdexImportResourceImpl implements NdexImportResource {

	private static final Logger logger = LoggerFactory.getLogger(NdexImportResourceImpl.class);

	private final NdexClient client;
	private final TaskMonitor tm;

	private CxTaskFactoryManager tfManager;

	private final CxReaderFactory loadNetworkTF;

	private final CyNetworkManager networkManager;
	private final CyApplicationManager appManager;

	private final ObjectMapper mapper;
	private final CIExceptionFactory ciExceptionFactory;
	private final CIErrorFactory ciErrorFactory;

	private final ErrorBuilder errorBuilder;
	
	public NdexImportResourceImpl(final NdexClient client, final ErrorBuilder errorBuilder, CyApplicationManager appManager, CyNetworkManager networkManager,
			CxTaskFactoryManager tfManager, TaskFactory loadNetworkTF,
			CIExceptionFactory ciExceptionFactory, CIErrorFactory ciErrorFactory) { 

		this.client = client;
		this.ciErrorFactory = ciErrorFactory;
		this.ciExceptionFactory = ciExceptionFactory;
		this.errorBuilder = errorBuilder;
		
		this.mapper = new ObjectMapper();

		this.networkManager = networkManager;
		this.appManager = appManager;

		this.tm = new HeadlessTaskMonitor();
		this.tfManager = tfManager;
		this.loadNetworkTF = (CxReaderFactory) loadNetworkTF;
	}

	@Override
	public NdexResponse<NdexImportResponse> createNetworkFromNdex(final NdexImportParams params) {

		// Prepare base response
		final NdexResponse<NdexImportResponse> response = new NdexResponse<>();

		// 1. Get summary of the network.
		Map<String, ?> summary = null;

		summary = client.getSummary(params.getServerUrl(), params.getUuid(), params.getUserId(), params.getPassword());

		System.out.println("* Got summary: " + summary);

		// Load network from ndex
		InputStream is;
		Long newSuid = null;

		is = client.load(params.getServerUrl() + "/network/" + params.getUuid(), params.getUserId(),
				params.getPassword());

		try {
			InputStreamTaskFactory readerTF = this.tfManager.getCxReaderFactory();
			TaskIterator itr = readerTF.createTaskIterator(is, "ndexCollection");
			CyNetworkReader reader = (CyNetworkReader) itr.next();
			TaskIterator tasks = loadNetworkTF.createTaskIterator(summary.get("name").toString(), reader);

			// Update table AFTER loading
			UpdateTableTask updateTableTask = new UpdateTableTask(reader);
			updateTableTask.setUuid(params.getUuid());
			tasks.append(updateTableTask);

			while (tasks.hasNext()) {
				final Task task = tasks.next();
				task.run(tm);
			}

			newSuid = reader.getNetworks()[0].getSUID();
		} catch (Exception e) {
			logger.error("Failed to load network from NDEx", e);
			Response res = errorBuilder.buildErrorResponse(Status.INTERNAL_SERVER_ERROR,
					"Failed to load network from NDEx.");
			throw new InternalServerErrorException(res);
		}

		response.setData(new NdexImportResponse(newSuid, params.getUuid()));
		return response;
	}

	
	
	@Override
	public NdexResponse<NdexSaveResponse> saveNetworkToNdex(Long suid, NdexSaveParams params) {

		final NdexResponse<NdexSaveResponse> response = new NdexResponse<>();

		if (suid == null) {
			logger.error("SUID is missing");
			Response res = errorBuilder.buildErrorResponse(Status.BAD_REQUEST, "SUID is not specified.");
			throw new InternalServerErrorException(res);
		}

		final CyNetwork network = networkManager.getNetwork(suid);
		if (network == null) {
			final String message = "Network with SUID " + suid + " does not exist.";
			logger.error(message);
			Response res = errorBuilder.buildErrorResponse(Status.NOT_FOUND, message);
			throw new InternalServerErrorException(res);
		}

		final CyNetworkViewWriterFactory writerFactory = tfManager.getCxWriterFactory();

		ByteArrayOutputStream os = new ByteArrayOutputStream();
		final CyWriter writer = writerFactory.createWriter(os, network);

		try {
			writer.run(new HeadlessTaskMonitor());
		} catch (Exception e) {
			final String message = "Failed to write network as CX";
			logger.error(message, e);
			Response res = errorBuilder.buildErrorResponse(Status.INTERNAL_SERVER_ERROR, message);
			throw new InternalServerErrorException(res);
		}

		// Upload to NDEx
		String networkName = network.getDefaultNetworkTable().getRow(network.getSUID()).get(CyNetwork.NAME,
				String.class);
		final ByteArrayInputStream cxis = new ByteArrayInputStream(os.toByteArray());
		String newUuid = null;

		newUuid = client.postNetwork(params.getServerUrl() + "/network", networkName, cxis, params.getUserId(),
				params.getPassword());

		if (newUuid == null || newUuid.isEmpty()) {
			final String message = "Failed to upload CX to NDEx.  (NDEx did not return UUID)";
			logger.error(message);
			Response res = errorBuilder.buildErrorResponse(Status.INTERNAL_SERVER_ERROR, message);
			throw new InternalServerErrorException(res);
		}

		System.out.println("============== New UUID: "+ newUuid);
		
		// Update table
		final Map<String, String> metadata = params.getMetadata();
		// Set New UUID
		metadata.put(NdexClient.UUID_COLUMN_NAME, newUuid);
		
		final CyRootNetwork root = ((CySubNetwork) network).getRootNetwork();
		final CyTable rootTable = root.getDefaultNetworkTable();
		

		metadata.keySet().stream().forEach(key -> saveMetadata(key, metadata.get(key), rootTable, root.getSUID()));

		// Visibility
		if (params.getIsPublic()) {
			// This is a hack: NDEx does not respond immediately after creation.
			try {
				Thread.sleep(500);
			} catch (InterruptedException e) {
				final String message = "Failed to wait (This should not happen!)";
				logger.error(message);
				Response res = errorBuilder.buildErrorResponse(Status.INTERNAL_SERVER_ERROR, message);
				throw new InternalServerErrorException(res);
			}

			client.setVisibility(params.getServerUrl(), newUuid, true, params.getUserId(), params.getPassword());
		}

		response.setData(new NdexSaveResponse(suid, newUuid));
		return response;
	}

	private final void saveMetadata(String columnName, String value, CyTable table, Long suid) {
		final CyColumn col = table.getColumn(columnName);

		if (col == null) {
			table.createColumn(columnName, String.class, false);
		}
		table.getRow(suid).set(columnName, value);
	}

	@Override
	public NdexResponse<NdexSaveResponse> saveCurrentNetworkToNdex(NdexSaveParams params) {
		final CyNetwork network = appManager.getCurrentNetwork();
		if (network == null) {
			// Current network is not available
			final String message = "Current network does not exist.  You need to choose a network first.";
			logger.error(message);
			final CIError ciError = 
					ciErrorFactory.getCIError(
							Status.BAD_REQUEST.getStatusCode(), 
							"urn:cytoscape:ci:ndex:v1:errors:1", 
							message,
							URI.create("file:///log"));
		    throw ciExceptionFactory.getCIException(
		    		Status.BAD_REQUEST.getStatusCode(), 
		    		new CIError[]{ciError});
		}

		return saveNetworkToNdex(network.getSUID(), params);
	}

	@CIWrapping
	@Override
	public SummaryResponse getCurrentNetworkSummary() {
		final CyNetwork network = appManager.getCurrentNetwork();
		
		if (network == null) {
			final String message = "Current network does not exist (No network is selected)";
			logger.error(message);
			Response res = errorBuilder.buildErrorResponse(Status.BAD_REQUEST, message);
			throw new BadRequestException(res);
		}
		
		final CyRootNetwork root = ((CySubNetwork) network).getRootNetwork();

		return buildSummary(root, (CySubNetwork) network);
	}

	private final SummaryResponse buildSummary(final CyRootNetwork root, final CySubNetwork network) {
		final SummaryResponse summary = new SummaryResponse();

		// Network local table
		final NetworkSummary rootSummary = buildNetworkSummary(root);
		summary.currentNetworkSuid = network.getSUID();
		summary.currentRootNetwork = rootSummary;
		List<NetworkSummary> members = new ArrayList<>();
		root.getSubNetworkList().stream().forEach(subnet -> members.add(buildNetworkSummary(subnet)));
		summary.members = members;

		return summary;
	}

	private final NetworkSummary buildNetworkSummary(final CyNetwork network) {
		CyTable table = network.getDefaultNetworkTable();
		NetworkSummary summary = new NetworkSummary();
		CyRow row = table.getRow(network.getSUID());
		summary.setSuid(row.get(CyNetwork.SUID, Long.class));
		summary.setName(network.getTable(CyNetwork.class, CyNetwork.LOCAL_ATTRS).getRow(network.getSUID())
				.get(CyNetwork.NAME, String.class));
		summary.setNdexUuid(row.get("ndex.uuid", String.class));

		final Collection<CyColumn> columns = table.getColumns();
		final Map<String, Object> props = new HashMap<>();

		columns.stream().forEach(col -> props.put(col.getName(), row.get(col.getName(), col.getType())));
		summary.setProps(props);

		return summary;
	}

	@Override
	public NdexResponse<NdexSaveResponse> updateNetworkInNdex(Long suid, NdexSaveParams params) {
		
		final NdexResponse<NdexSaveResponse> response = new NdexResponse<>();

		if (suid == null) {
			logger.error("SUID is missing");
			Response res = errorBuilder.buildErrorResponse(Status.BAD_REQUEST, "SUID is not specified.");
			throw new InternalServerErrorException(res);
		}

		final CyNetwork network = networkManager.getNetwork(suid);
		if (network == null) {
			final String message = "Network with SUID " + suid + " does not exist.";
			logger.error(message);
			Response res = errorBuilder.buildErrorResponse(Status.NOT_FOUND, message);
			throw new InternalServerErrorException(res);
		}
		
		// Check UUID
		final CyRootNetwork root = ((CySubNetwork)network).getRootNetwork();
		final String uuid = root.getDefaultNetworkTable().getRow(root.getSUID()).get("ndex.uuid", String.class);
		
		System.out.println("============== Target UUID: "+ uuid);
		
		// Update table
		final Map<String, String> metadata = params.getMetadata();
		final CyTable rootTable = root.getDefaultNetworkTable();
		metadata.keySet().stream().forEach(key -> saveMetadata(key, metadata.get(key), rootTable, root.getSUID()));

		final CyNetworkViewWriterFactory writerFactory = tfManager.getCxWriterFactory();

		ByteArrayOutputStream os = new ByteArrayOutputStream();
		final CyWriter writer = writerFactory.createWriter(os, network);

		try {
			writer.run(new HeadlessTaskMonitor());
		} catch (Exception e) {
			final String message = "Failed to write network as CX";
			logger.error(message, e);
			Response res = errorBuilder.buildErrorResponse(Status.INTERNAL_SERVER_ERROR, message);
			throw new InternalServerErrorException(res);
		}

		// Upload to NDEx
		String networkName = network.getDefaultNetworkTable().getRow(network.getSUID()).get(CyNetwork.NAME,
				String.class);
		final ByteArrayInputStream cxis = new ByteArrayInputStream(os.toByteArray());

		try {
			// Ndex client from NDEx Team
			final NdexRestClient nc = new NdexRestClient(params.getUserId(), params.getPassword(),
					params.getServerUrl());
			final NdexRestClientModelAccessLayer ndex = new NdexRestClientModelAccessLayer(nc);
			ndex.updateCXNetwork(UUID.fromString(uuid), cxis);
		} catch (Exception e1) {
			e1.printStackTrace();
			final String message = "Failed to update network from CX";
			logger.error(message, e1);
			Response res = errorBuilder.buildErrorResponse(Status.INTERNAL_SERVER_ERROR, message);
			throw new InternalServerErrorException(res);
		}

//		client.updateNetwork(params.getServerUrl(), uuid, networkName, cxis, params.getUserId(),
//				params.getPassword());

		System.out.println("============== UPDATED!!!!!!!: "+ uuid);


		// Visibility
		if (params.getIsPublic()) {
			// This is a hack: NDEx does not respond immediately after creation.
			try {
				Thread.sleep(500);
			} catch (InterruptedException e) {
				final String message = "Failed to wait (This should not happen!)";
				logger.error(message);
				Response res = errorBuilder.buildErrorResponse(Status.INTERNAL_SERVER_ERROR, message);
				throw new InternalServerErrorException(res);
			}

			client.setVisibility(params.getServerUrl(), uuid, true, params.getUserId(), params.getPassword());
		}

		response.setData(new NdexSaveResponse(suid, uuid));
		return response;
	}

	@Override
	public NdexResponse<NdexSaveResponse> updateCurrentNetworkInNdex(NdexSaveParams params) {
		final CyNetwork network = appManager.getCurrentNetwork();
		
		if (network == null) {
			final String message = "Current network does not exist (No network is selected)";
			logger.error(message);
			Response res = errorBuilder.buildErrorResponse(Status.BAD_REQUEST, message);
			throw new BadRequestException(res);
		}
		
		return updateNetworkInNdex(network.getSUID(), params);
	}
}
