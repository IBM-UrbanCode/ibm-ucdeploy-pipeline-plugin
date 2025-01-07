/**
 * (c) Copyright IBM Corporation 2017.
 * This is licensed under the following license.
 * The Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0)
 * U.S. Government Users Restricted Rights:  Use, duplication or disclosure restricted by GSA ADP Schedule Contract with IBM Corp.
 */

package com.urbancode.jenkins.plugins.ucdeploy;

import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import java.io.BufferedReader;
import org.apache.http.HttpEntity;
import org.apache.http.util.EntityUtils;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.model.TaskListener;
import hudson.slaves.EnvironmentVariablesNodeProperty;
import hudson.slaves.NodeProperty;
import hudson.slaves.NodePropertyDescriptor;
import hudson.util.DescribableList;
import jenkins.model.Jenkins;
import javax.ws.rs.core.UriBuilder;
import java.io.IOException;
import java.lang.InterruptedException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Map;
import java.util.Set;

import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.client.methods.HttpRequestBase;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;

import com.urbancode.jenkins.plugins.ucdeploy.ProcessHelper;
import com.urbancode.jenkins.plugins.ucdeploy.ProcessHelper.CreateProcessBlock;
import com.urbancode.ud.client.ApplicationClient;
import javax.net.ssl.HttpsURLConnection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class is used to provide access to the UrbanCode Deploy rest client
 * and run component version related rest calls
 *
 */
@SuppressWarnings("deprecation") // Triggered by DefaultHttpClient
public class DeployHelper {
    public static final Logger log = LoggerFactory.getLogger(DeployHelper.class);
    private ApplicationClient appClient;
    private TaskListener listener;
    private EnvVars envVars;
    private URI ucdUrl;
    private boolean skipProps;

    public DeployHelper(URI ucdUrl, DefaultHttpClient httpClient, TaskListener listener, EnvVars envVars, boolean skipProps) {
        this.ucdUrl = ucdUrl;
    	appClient = new ApplicationClient(ucdUrl, httpClient);
        this.listener = listener;
        this.envVars = envVars;
        this.skipProps = skipProps;
    }

    public static class DeployBlock {
        private String deployApp;
        private String deployEnv;
        private String deployProc;
        private Boolean skipWait;
        private CreateProcessBlock createProcess;
        private CreateSnapshotBlock createSnapshot;
        private String deployVersions;
        private String deployReqProps;
        private String deployDesc;
        private Boolean deployOnlyChanged;

        @DataBoundConstructor
        public DeployBlock(
            String deployApp,
            String deployEnv,
            String deployProc,
            Boolean skipWait,
            CreateProcessBlock createProcess,
            CreateSnapshotBlock createSnapshot,
            String deployVersions,
            String deployReqProps,
            String deployDesc,
            Boolean deployOnlyChanged)
        {
            this.deployApp = deployApp;
            this.deployEnv = deployEnv;
            this.deployProc = deployProc;
            this.skipWait = skipWait;
            this.createProcess = createProcess;
            this.createSnapshot = createSnapshot;
            this.deployVersions = deployVersions;
            this.deployReqProps = deployReqProps;
            this.deployDesc = deployDesc;
            this.deployOnlyChanged = deployOnlyChanged;
        }

        public String getDeployApp() {
            if (deployApp != null) {
                return deployApp;
            }
            else {
                return "";
            }
        }

        public String getDeployEnv() {
            if (deployEnv != null) {
                return deployEnv;
            }
            else {
                return "";
            }
        }

        public String getDeployProc() {
            if (deployProc != null) {
                return deployProc;
            }
            else {
                return "";
            }
        }

        public CreateProcessBlock getCreateProcess() {
            return createProcess;
        }

        public Boolean getSkipWait() {
            if (skipWait != null) {
                return skipWait;
            }
            else {
                return false;
            }
        }

        public Boolean createProcessChecked() {
            if (getCreateProcess() == null) {
                return false;
            }
            else {
                return true;
            }
        }

        public CreateSnapshotBlock getCreateSnapshot() {
            return createSnapshot;
        }

        public Boolean createSnapshotChecked() {
            if (getCreateSnapshot() == null) {
                return false;
            }
            else {
                return true;
            }
        }

        public String getDeployVersions() {
            if (deployVersions != null) {
                return deployVersions;
            }
            else {
                return "";
            }
        }

        public String getDeployReqProps() {
            if (deployReqProps != null) {
                return deployReqProps;
            }
            else {
                return "";
            }
        }

        public String getDeployDesc() {
            if (deployDesc != null) {
                return deployDesc;
            }
            else {
                return "";
            }
        }

        public Boolean getDeployOnlyChanged() {
            if (deployOnlyChanged != null) {
                return deployOnlyChanged;
            }
            else {
                return false;
            }
        }

        public String getMethod(String uri) throws Exception{
            log.info("[UrbanCode Deploy] uri: " + uri);
            String result = "";
            HttpGet method = new HttpGet(uri);
            try {
                HttpResponse response = UCDeploySite.client.execute(method);
                int responseCode = response.getStatusLine().getStatusCode();
                if (responseCode == 401) {
                    throw new Exception("Error connecting to IBM UrbanCode Deploy: Invalid user and/or password");
                }
                else if (responseCode != 200) {
                    throw new Exception("Error connecting to IBM UrbanCode Deploy: " + responseCode + "using URI: " + uri.toString());
                }
                HttpEntity entity = response.getEntity();
                if (entity != null) {
                    // return it as a String
                    result = EntityUtils.toString(entity);
                }
            }catch (Exception e) {
                e.printStackTrace();
            }
            finally {
                method.releaseConnection();
            }
            log.info("[UrbanCode Deploy] result: " + result);
            return result;
        }

        public void createGlobalEnvironmentVariables(String key, String value) {

            Jenkins instance = Jenkins.getInstance();

            DescribableList<NodeProperty<?>, NodePropertyDescriptor> globalNodeProperties = instance.getGlobalNodeProperties();
            List<EnvironmentVariablesNodeProperty> envVarsNodePropertyList = globalNodeProperties.getAll(EnvironmentVariablesNodeProperty.class);

            EnvironmentVariablesNodeProperty newEnvVarsNodeProperty = null;
            EnvVars envVars = null;

            if ( envVarsNodePropertyList == null || envVarsNodePropertyList.size() == 0 ) {
                newEnvVarsNodeProperty = new hudson.slaves.EnvironmentVariablesNodeProperty();
                globalNodeProperties.add(newEnvVarsNodeProperty);
                envVars = newEnvVarsNodeProperty.getEnvVars();
            } else {
                envVars = envVarsNodePropertyList.get(0).getEnvVars();
            }
            envVars.put(key, value);
            try {
                instance.save();
            } catch(Exception e) {
                System.out.println("Failed to create env variable"+e);
            }
        }
    }

    public static class CreateSnapshotBlock {
        private String snapshotName;
        private Boolean deployWithSnapshot;
        private Boolean updateSnapshotComp;
        //Aded
        private Boolean createSnapshotComp;
        private Boolean includeOnlyDeployVersions;

        @DataBoundConstructor
        public CreateSnapshotBlock(String snapshotName, Boolean deployWithSnapshot, Boolean updateSnapshotComp, Boolean includeOnlyDeployVersions) {
            this.snapshotName = snapshotName;
            this.deployWithSnapshot = deployWithSnapshot;
            this.updateSnapshotComp = updateSnapshotComp;
            // Added
            this.createSnapshotComp = createSnapshotComp;
            this.includeOnlyDeployVersions = includeOnlyDeployVersions;
        }

        public String getSnapshotName() {
            return snapshotName;
        }

        public Boolean getDeployWithSnapshot() {
            if (deployWithSnapshot != null) {
                return deployWithSnapshot;
            }
            else {
                return false;
            }
        }

        public Boolean getUpdateSnapshotComp() {
            if (updateSnapshotComp != null) {
                return updateSnapshotComp;
            }
            else {
                return false;
            }
        }
        
        // Added
        public Boolean createSnapshotComp() {
            if (createSnapshotComp != null) {
                return createSnapshotComp;
            }
            else {
                return false;
            }
        }


        public Boolean getIncludeOnlyDeployVersions() {
            if (includeOnlyDeployVersions != null) {
                return includeOnlyDeployVersions;
            }
            else {
                return false;
            }
        }
    }

    /**
     * Deploys a version in IBM UrbanCode Deploys
     *
     * @param deployBlock The DeployBlock containing the structure of the deployment
     * @throws JSONException
     * @throws IOException
     */
    public void runDeployment(DeployBlock deployBlock) throws IOException, JSONException {
        String deployApp = envVars.expand(deployBlock.getDeployApp());
        String deployEnv = envVars.expand(deployBlock.getDeployEnv());
        String deployProc = envVars.expand(deployBlock.getDeployProc());
        Boolean skipWait = deployBlock.getSkipWait();
        String deployVersions = envVars.expand(deployBlock.getDeployVersions());
        String deployReqProps = envVars.expand(deployBlock.getDeployReqProps());
        String deployDesc = envVars.expand(deployBlock.getDeployDesc());
        CreateSnapshotBlock createSnapshot = deployBlock.getCreateSnapshot();
        Boolean doCreateSnapshot = deployBlock.createSnapshotChecked();
        Map<String, String> requestProperties = readProperties(deployReqProps);

        // create process
        if (deployBlock.createProcessChecked()) {
            ProcessHelper processHelper = new ProcessHelper(appClient, listener, envVars);
            processHelper.createProcess(deployApp, deployProc, deployBlock.getCreateProcess());
        }

        // required fields
        if (deployApp.isEmpty()) {
            throw new AbortException("Deploy Application is a required field for deployment.");
        }
        if (deployEnv.isEmpty()) {
            throw new AbortException("Deploy Environment is a required field for deployment.");
        }
        if (deployProc.isEmpty()) {
            throw new AbortException("Deploy Process is a required field for deployment.");
        }
        
        /*Commenting to support following :
           1. Operational component process which needs no version.
           2. Running application generic process 
        */
        /*
        if (deployVersions.isEmpty()) {
            throw new AbortException("Deploy Versions is a required field for deployment.");
        }
        */

        /* Deploy logic */
        String snapshot = "";
        Map<String, List<String>> componentVersions = new HashMap<String, List<String>>();
        UUID appProcUUID;

        /* Create snapshot preemptively to deploy */
        if (doCreateSnapshot && createSnapshot.getDeployWithSnapshot()) {
            listener.getLogger().println("[ !!!!! IF !!!!! ]");
            snapshot = envVars.expand(createSnapshot.getSnapshotName());
            doCreateSnapshot = false; // Set to false so reactive snapshot isn't created also

            if (deployVersions.toUpperCase().startsWith("SNAPSHOT=")) {
                listener.getLogger().println("[Warning] When deploying with a build environment snapshot,"
                        + " you may not specify additional snapshots in the 'Snapshot/Component Versions' box."
                        + " This field will be ignored for this deployment.");
            }
            else {
                componentVersions = readComponentVersions(deployVersions);  // Versions to add to new snapshot
                listener.getLogger().println("[componentVersions] '" + componentVersions + "'");
            }

            listener.getLogger().println("Creating environment snapshot '" + snapshot
                    + "' in UrbanCode Deploy.");

            try {
                if (createSnapshot.getIncludeOnlyDeployVersions()) {
                    listener.getLogger().println("[5555555555 - IF]");
                    appClient.createSnapshot(snapshot, deployDesc, deployApp, componentVersions);
                } else {
                    listener.getLogger().println("[5555555555 - ELSE]");
                    listener.getLogger().println("[snapshot---11111] '" + snapshot + "'");
                    listener.getLogger().println("[deployDesc---11111] '" + deployDesc + "'");
                    appClient.createSnapshotOfEnvironment(deployEnv, deployApp, snapshot, deployDesc);
                }
            } catch (Exception ex) {
                String exMessage = ex.getMessage();
                String checkString = "Snapshot with name " + snapshot + " already exists for this application";
                if (exMessage.contains(checkString) && createSnapshot.getUpdateSnapshotComp()) {
                    listener.getLogger().println("Snapshot already exist, updating environment snapshot '" + snapshot
                    + "' in UrbanCode Deploy.");
                } else {
                    throw new AbortException(ex.getMessage());
                }
            }

            listener.getLogger().println("Acquiring all versions of the snapshot.");
            JSONArray snapshotVersions = appClient.getSnapshotVersions(snapshot, deployApp);
            listener.getLogger().println("[snapshotVersions] '" + snapshotVersions + "'");

            Map<String, JSONArray> compVersionMap = new HashMap<String, JSONArray>();

            /* Create a map of component name to a list of its versions in the snapshot */
            for (int i = 0; i < snapshotVersions.length(); i++) {
                listener.getLogger().println("[11111]");
                JSONObject snapshotComponent = snapshotVersions.getJSONObject(i);
                String name = snapshotComponent.getString("name");
                JSONArray versions = snapshotComponent.getJSONArray("desiredVersions");

                compVersionMap.put(name, versions);
            }

            for (Map.Entry<String, List<String>> entry : componentVersions.entrySet()) {
                String component = entry.getKey();
                JSONArray oldVersions = compVersionMap.get(component);

                /* Remove past versions of the deployment component from the snapshot */
                if (oldVersions != null && oldVersions.length() > 0) {
                    for (int i = 0 ; i < oldVersions.length(); i++) {
                        JSONObject oldVersion = oldVersions.getJSONObject(i);
                        String oldVersionName = oldVersion.getString("name");
                        String oldVersionId = oldVersion.getString("id");

                        listener.getLogger().println("Removing past version '" + oldVersionName +
                                "' of component '" + component + "' from snapshot.");
                        appClient.removeVersionFromSnapshot(snapshot, deployApp, oldVersionId, component);
                    }
                }

                /* Add each version for this component to the snapshot */
                for (String version : entry.getValue()) {
                    listener.getLogger().println("Adding component version '" + version +
                            "' of component '" + component + "' to snapshot.");
                     // will uncomment this later       
                    appClient.addVersionToSnapshot(snapshot, deployApp, version, component);
                }
            }

            listener.getLogger().println("Deploying SNAPSHOT '" + snapshot + "'");
        }
        /* Deploy with component versions or a pre-existing snapshot */
        else {
            listener.getLogger().println("[ !!!!! ELSE !!!!! ]");
            if (deployVersions.toUpperCase().startsWith("SNAPSHOT=")) {
                if (deployVersions.contains("\n")) {
                    throw new AbortException("Only a single SNAPSHOT can be specified");
                }
                snapshot = deployVersions.replaceFirst("(?i)SNAPSHOT=", "");
                listener.getLogger().println("Deploying SNAPSHOT '" + snapshot + "'");
            }
            else {
                componentVersions = readComponentVersions(deployVersions);
                listener.getLogger().println("Deploying component versions '" + componentVersions + "'");
            }
        }

        appProcUUID = deploy(deployApp, deployProc, deployDesc, deployEnv, snapshot, componentVersions,
                deployBlock.getDeployOnlyChanged(), requestProperties);

        listener.getLogger().println("Starting deployment process '" + deployProc + "' of application '" + deployApp +
                                     "' in environment '" + deployEnv + "'");


        listener.getLogger().println("Deployment request id is: '" + appProcUUID.toString() + "'");
        listener.getLogger().println("Deployment is running. Waiting for UCD Server feedback.");
       
        long startTime = new Date().getTime();
        boolean processFinished = false;
        String deploymentResult = "";

        /* Wait for process to finish unless skipping the wait */
        if (!skipWait) {
            while (!processFinished) {
                deploymentResult = checkDeploymentProcessResult(appProcUUID.toString());

                if (!deploymentResult.isEmpty()
                        && !deploymentResult.equalsIgnoreCase("NONE")
                        && !deploymentResult.equalsIgnoreCase("SCHEDULED FOR FUTURE")) {
                    processFinished = true;

                    if (deploymentResult.equalsIgnoreCase("FAULTED") || deploymentResult.equalsIgnoreCase("FAILED TO START") || deploymentResult.equalsIgnoreCase("CANCELED")) {
                        throw new AbortException("Deployment process failed with result " + deploymentResult);
                    }
                }

                // give application process more time to complete
                try {
                    Thread.sleep(3000);
                }
                catch (InterruptedException ex) {
                    throw new AbortException("Could not wait to check deployment result: " + ex.getMessage());
                }
            }
        }
        else {
            listener.getLogger().println("'Skip Wait' option selected. Returning immmediately "
                    + "without waiting for the UCD process to complete.");
        }

        /* create snapshot of environment reactively, as a result of successful deployment */
        if (doCreateSnapshot) {
            listener.getLogger().println("[ !!!!! finally creating the snapshot !!!!! ]");
            String snapshotName = envVars.expand(createSnapshot.getSnapshotName());
            listener.getLogger().println("Creating environment snapshot '" + snapshotName
                    + "' in UrbanCode Deploy.");
            // will uncomment later        
            // appClient.createSnapshotOfEnvironment(deployEnv, deployApp, snapshotName, deployDesc);
            listener.getLogger().println("Successfully created environment snapshot.");
        }

        long duration = (new Date().getTime() - startTime) / 1000;

        listener.getLogger().println("Finished the deployment in " + duration + " seconds");
        listener.getLogger().println("The deployment result is " + deploymentResult + ". See the UrbanCode Deploy deployment " +
                                     "logs for details : " + ucdUrl + "/#applicationProcessRequest/" + appProcUUID.toString());
        
        listener.getLogger().println("Starting Application Property Fetching...");
        try{
            URI uri = UriBuilder.fromPath(ucdUrl.toString()).path("rest").path("deploy").path("application").build();
            String data = deployBlock.getMethod(uri.toString());
            String applicationId ="";
            JSONArray array = new JSONArray(data);  
                for(int i=0; i < array.length(); i++)   
                {  
                    if(array.getJSONObject(i).getString("name").equalsIgnoreCase(deployApp.toString())){
                        applicationId = array.getJSONObject(i).getString("id");
                        break;
                    }
                }
            listener.getLogger().println("APPLICATION ID is " + applicationId);
            if(applicationId!= ""){
                URI uri1 = UriBuilder.fromPath(ucdUrl.toString()).path("rest").path("deploy").path("application").path(applicationId).build();
                String data1 = deployBlock.getMethod(uri1.toString());
                
                JSONObject objectData = new JSONObject(data1);
                JSONObject propSheet = objectData.getJSONObject("propSheet");
                String versionCount = propSheet.getString("versionCount");
                // find Application property 
                String uri2 = ucdUrl.toString()+"/property/propSheet/applications%26"+applicationId+"%26propSheet."+versionCount;
                String data2 = deployBlock.getMethod(uri2);
                JSONObject PropertyObject = new JSONObject(data2);
                JSONArray array1 = new JSONArray(PropertyObject.getString("properties"));
                listener.getLogger().println("********** isSkipProps value is " + skipProps);
                if (skipProps == false) {
                    for(int i=0; i < array1.length(); i++)
                    {  
                        if(array1.getJSONObject(i).getString("secure") == "false"){
                            listener.getLogger().println("Env : "+array1.getJSONObject(i).getString("name")+"="+array1.getJSONObject(i).getString("value"));
                            deployBlock.createGlobalEnvironmentVariables(array1.getJSONObject(i).getString("name"),array1.getJSONObject(i).getString("value"));
                        }
                    }
                }
            }
        }catch (Exception e) {
                listener.getLogger().println(e);
        }
        listener.getLogger().println("End Application Property Fetching.");
    }

    private UUID deploy(
            String application,
            String appProcess,
            String description,
            String environment,
            String snapshot,
            Map<String, List<String>> componentVersions,
            Boolean deployOnlyChanged,
            Map<String, String> requestProperties)
    throws IOException, JSONException {

        // Confirm all application request properties are fulfilled (not done by UCD)
        JSONArray unfilledProps = appClient.checkUnfilledApplicationProcessRequestProperties(application,
                appProcess, snapshot, requestProperties);
        if (unfilledProps.length() > 0) {
            List<String> props = new ArrayList<String>();
            for (int i = 0; i < unfilledProps.length(); i++) {
                String propName = unfilledProps.getJSONObject(i).getString("name");
                props.add(propName);
            }
            throw new AbortException("Required UrbanCode Deploy Application Process request properties were "
                    + "not supplied: " + props.toString());
        }

        // Run the application process
        UUID appProcUUID = appClient.requestApplicationProcess(application,
                                                          appProcess,
                                                          description,
                                                          environment,
                                                          snapshot,
                                                          deployOnlyChanged,
                                                          componentVersions,
                                                          requestProperties);
        return appProcUUID;
    }

    /**
     * Convert string of newline separated component:version to HashMap required by AppClient
     *
     * @param comopnentVersionsRaw
     * @return A HashMap containing the components with their version lists
     * @throws AbortException
     */
    private Map<String, List<String>> readComponentVersions(String componentVersionsRaw) throws AbortException {
        Map<String, List<String>> componentVersions = new HashMap<String, List<String>>();

        for (String cvLine : componentVersionsRaw.split("\n")) {
            if(!cvLine.isEmpty() && cvLine != null) {
                int delim = cvLine.indexOf(':');

                if (delim <= 0) {
                    throw new AbortException("Component/version pairs must be of the form {Component}:{Version #}");
                }

                String component = cvLine.substring(0, delim).trim();

                List<String> versionList = componentVersions.get(component);

                // create new list of versions if no versions have been added
                if (versionList == null) {
                    versionList = new ArrayList<String>();
                    componentVersions.put(component, versionList);
                }

                // update existing list of versions
                String version = cvLine.substring(delim + 1).trim();
                versionList.add(version);
            }
        }

        return componentVersions;
    }

    /**
     * Load properties into a properties map
     *
     * @param properties The unparsed properties to load
     * @return The loaded properties map
     * @throws AbortException
     */
    private Map<String, String> readProperties(String properties) throws AbortException {
        Map<String, String> propertiesToSet = new HashMap<String, String>();
        if (properties != null && properties.length() > 0) {
            for (String line : properties.split("\n")) {
                String[] propDef = line.split("=");

                if (propDef.length >= 2) {
                    String propName = propDef[0].trim();
                    String propVal = propDef[1].trim();
                    propertiesToSet.put(propName, propVal);
                }
                else {
                    throw new AbortException("Missing property delimiter '=' in property definition '" + line + "'");
                }
            }
        }
        return propertiesToSet;
    }

    /**
     * Check the result of an application process
     *
     * @param appClient
     * @param procId
     * @return A boolean value stating whether the process is finished
     * @throws AbortException
     */
    private String checkDeploymentProcessResult(String procId)
    throws AbortException {
        String deploymentResult;

        try {
            deploymentResult = appClient.getApplicationProcessStatus(procId);
        }
        catch (Exception ex) {
            throw new AbortException("Failed to acquire status of application process with id '" + procId + "' : "
                                     + ex.getMessage());
        }

        return deploymentResult;
    }
}
