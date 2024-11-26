/**
 * (c) Copyright IBM Corporation 2017.
 * This is licensed under the following license.
 * The Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0)
 * U.S. Government Users Restricted Rights:  Use, duplication or disclosure restricted by GSA ADP Schedule Contract with IBM Corp.
 */

package com.urbancode.jenkins.plugins.ucdeploy;

import hudson.AbortException;
import hudson.EnvVars;
import hudson.model.TaskListener;
import hudson.slaves.EnvironmentVariablesNodeProperty;
import hudson.slaves.NodeProperty;
import hudson.slaves.NodePropertyDescriptor;
import hudson.util.DescribableList;
import jenkins.model.Jenkins;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.nio.charset.Charset;

import org.apache.http.impl.client.DefaultHttpClient;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.apache.commons.lang3.StringUtils;

import com.urbancode.jenkins.plugins.ucdeploy.ComponentHelper.CreateComponentBlock;
import com.urbancode.jenkins.plugins.ucdeploy.DeliveryHelper.DeliveryBlock;
import com.urbancode.jenkins.plugins.ucdeploy.DeliveryHelper.Pull;
import com.urbancode.jenkins.plugins.ucdeploy.DeliveryHelper.Push;
import com.urbancode.ud.client.ApplicationClient;
import com.urbancode.ud.client.ComponentClient;
import com.urbancode.ud.client.PropertyClient;
import com.urbancode.ud.client.VersionClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class provides the structure and function around version control in IBM
 * UrbanCode Deploy via uDeployRestClient abstracted REST calls import
 * org.codehaus.jettison.json.JSONException;
 *
 */
@SuppressWarnings("deprecation") // Triggered by DefaultHttpClient
public class VersionHelper {
    public static final Logger log = LoggerFactory.getLogger(VersionHelper.class);
    private ApplicationClient appClient;
    private ComponentClient compClient;
    private PropertyClient propClient;
    private VersionClient verClient;
    private TaskListener listener;
    private EnvVars envVars;

    public VersionHelper(URI ucdUrl, DefaultHttpClient httpClient, TaskListener listener, EnvVars envVars) {
        appClient = new ApplicationClient(ucdUrl, httpClient);
        compClient = new ComponentClient(ucdUrl, httpClient);
        propClient = new PropertyClient(ucdUrl, httpClient);
        verClient = new VersionClient(ucdUrl, httpClient);
        this.listener = listener;
        this.envVars = envVars;
    }

    public static class VersionBlock implements Serializable {
        private String componentName;
        private String componentTag;
        private CreateComponentBlock createComponent;
        private DeliveryBlock delivery;

        @DataBoundConstructor
        public VersionBlock(String componentName,
                            String componentTag,
                            CreateComponentBlock createComponent,
                            DeliveryBlock delivery)
        {
            this.componentName = componentName;
            this.componentTag = componentTag;

            this.createComponent = createComponent;
            this.delivery = delivery;
        }

        public String getComponentName() {
            return componentName;
        }

        public String getComponentTag() {
            if (componentTag != null) {
                return componentTag;
            }
            else {
                return "";
            }
        }

        public CreateComponentBlock getCreateComponent() {
            return createComponent;
        }

        public Boolean createComponentChecked() {
            if (getCreateComponent() == null) {
                return false;
            }
            else {
                return true;
            }
        }

        public DeliveryBlock getDelivery() {
            return delivery;
        }
    }

    /**
     * Creates a new component version, either pushing from Jenkins or triggering source config pull
     *
     * @param versionBlock The object containing the data strucutre of the version
     * @param linkName The name to give the component version link
     * @param linkUrl The url to link as a component version
     * @throws AbortException
     *
     */
    public void createVersion(VersionBlock versionBlock, String linkName, String linkUrl) throws AbortException {
        ComponentHelper componentHelper = new ComponentHelper(appClient, compClient, listener, envVars);
        String componentName = envVars.expand(versionBlock.getComponentName());
        String componentTag = envVars.expand(versionBlock.getComponentTag());

        if (componentName == null || componentName.isEmpty()) {
            throw new AbortException("Component Name is a required property.");
        }

        // create component
        if (versionBlock.createComponentChecked()) {
        	log.info("[This is a test log]...");
            log.info("[UrbanCode Deploy] create component starts...");
            componentHelper.createComponent(componentName,
                                            versionBlock.getCreateComponent(),
                                            versionBlock.getDelivery());
            log.info("[UrbanCode Deploy] create component ends...");
        }

        // tag component
        if (componentTag != null && !componentTag.isEmpty()) {
            log.info("[UrbanCode Deploy] tag component starts...");
            componentHelper.addTag(componentName, componentTag);
            log.info("[UrbanCode Deploy] tag component ends...");
        }

        // create version and upload files
        if (versionBlock.getDelivery().getDeliveryType() == DeliveryBlock.DeliveryType.Push) {
            log.info("[UrbanCode Deploy] create version and upload files starts...");
            Push pushBlock = (Push)versionBlock.getDelivery();
            String version = envVars.expand(pushBlock.getPushVersion());
            listener.getLogger().println("Creating new component version and Uploading files to version '" + version + "' on component '" + componentName +
                                         "'");
            log.info("Creating new component version and Uploading files to version '" + version + "' on component '" + componentName + "'");
            if (version == null || version.isEmpty() || version.length() > 255) {
                throw new AbortException("Failed to create version '" + version + "' in UrbanCode Deploy. UrbanCode Deploy " +
                                         "version name length must be between 1 and  255 characters long. (Current length: " +
                                         version.length() + ")");
            }

            UUID versionId;
            File base = new File(envVars.expand(pushBlock.getBaseDir()));
            if (!base.exists()) {
                throw new AbortException("Base artifact directory " + base.getAbsolutePath() + " does not exist");
            }
            
            if(base.list().length==0) {
                throw new AbortException("Base artifact directory " + base.getAbsolutePath() + " does not contain any files to upload. Please place files.");
            }
            String[] includes = splitFiles(envVars.expand(pushBlock.getFileIncludePatterns()));
            String[] excludes = splitFiles(envVars.expand(pushBlock.getFileExcludePatterns()));
            String[] extensions = splitFiles(envVars.expand(pushBlock.getExtensions()));
            String charsetString = envVars.expand(pushBlock.getCharset());
            Charset charset = Charset.defaultCharset();
            if (!StringUtils.isBlank(charsetString)) {
                listener.getLogger().println("Charset is provided... " + charsetString);
                charset = Charset.forName(charsetString);
                listener.getLogger().println("Charset Display Name: " + charset.displayName());
            }
            try {
                versionId = verClient.createAndAddVersionFiles(componentName, version, envVars.expand(pushBlock.getPushDescription()), base, "", includes, excludes, true, true, charset, extensions);
            }
            catch (Exception ex) {
                throw new AbortException("Failed to create component version and uploading files: " + ex.getMessage());
            }
            listener.getLogger().println("Successfully created component version with UUID '" + versionId.toString() + "' and uploaded files.");

            try {
                putEnvVar(componentName + "_VersionId", versionId.toString());
            }
            catch (Exception ex) {
                listener.getLogger().println("[Warning] Failed to set version ID as environment variable.");
            }
            log.info("[UrbanCode Deploy] create version and upload files ends...");

            // set version properties
            listener.getLogger().println("Setting properties for version '" + version + "' on component '" + componentName + "'");
            log.info("[UrbanCode Deploy] set version properties starts...");
            setComponentVersionProperties(componentName,
                                          version,
                                          DeliveryBlock.mapProperties(envVars.expand(pushBlock.getPushProperties())));
            log.info("[UrbanCode Deploy] set version properties ends...");

            // add link
            listener.getLogger().println("Creating component version link '" + linkName + "' to URL '" + linkUrl + "'");
            try {
                log.info("[UrbanCode Deploy] add link starts...");
                compClient.addComponentVersionLink(componentName, version, linkName, linkUrl);
                log.info("[UrbanCode Deploy] add link ends...");
            }
            catch (Exception ex) {
                log.info("[UrbanCode Deploy] add link failed...");
                log.info("Failed to add a version link: " + ex.getMessage());
                throw new AbortException("Failed to add a version link: " + ex.getMessage());
            }
        }

        // import version
        else if (versionBlock.getDelivery().getDeliveryType() == DeliveryBlock.DeliveryType.Pull) {
            Pull pullBlock = (Pull)versionBlock.getDelivery();

            Map<String, String> mappedProperties = DeliveryBlock.mapProperties(envVars.expand(pullBlock.getPullProperties()));
            listener.getLogger().println("Using runtime properties " + mappedProperties);

            try {
                log.info("[UrbanCode Deploy] import version starts...");
                compClient.importComponentVersions(componentName, mappedProperties);
                log.info("[UrbanCode Deploy] import version ends...");
            }
            catch (IOException ex) {
                log.info("[UrbanCode Deploy] import version failed...");
                log.info("An error occurred while importing component versions on component '" + componentName + "' : " + ex.getMessage());
                throw new AbortException("An error occurred while importing component versions on component '" + componentName +
                                         "' : " + ex.getMessage());
            }
            catch (JSONException ex) {
                log.info("[UrbanCode Deploy] import version failed...");
                log.info("An error occurred while creating JSON version import object : " + ex.getMessage());
                throw new AbortException("An error occurred while creating JSON version import object : " + ex.getMessage());
            }
        }

        // invalid type
        else {
            log.info("[UrbanCode Deploy] invalid type...");
            log.info("Invalid Delivery Type: " + versionBlock.getDelivery().getDeliveryType());
            throw new AbortException("Invalid Delivery Type: " + versionBlock.getDelivery().getDeliveryType());
        }
    }

    /**
     * Upload files to component version
     *
     * @param baseDir The base directory of the files to upload
     * @param component The component to upload the files to
     * @param version The version of the component to upload the files to
     * @param includePatterns The patterns to include in the upload
     * @param excludePatterns The patterns to exclude in the upload
     * @throws AbortException
     */
    public void uploadVersionFiles(
        String baseDir,
        String component,
        String version,
        String includePatterns,
        String excludePatterns)
    throws AbortException {
        String[] includes = splitFiles(includePatterns);
        String[] excludes = splitFiles(excludePatterns);

        File base = new File(baseDir);

        if (!base.exists()) {
            throw new AbortException("Base artifact directory " + base.getAbsolutePath() + " does not exist");
        }
        
        if(base.list().length==0) {
        	throw new AbortException("Base artifact directory " + base.getAbsolutePath() + " does not contain any files to upload. Please place files.");
        }

        try {
            verClient.addVersionFiles(component,
                                      version,
                                      base,
                                      "",
                                      includes,
                                      excludes,
                                      true,
                                      true);
        }
        catch (Exception ex) {
            throw new AbortException("Failed to upload files: " + ex.getMessage());
        }
    }

    /**
     * Set environment variable.
     * @param key
     * @param value
     * @throws IOException
     */
    private void putEnvVar(String key, String value) throws IOException {
        key = key.replaceAll(" ", "_");
        listener.getLogger().println("Setting environment variable " + key + ".");
        Jenkins jenkins = Jenkins.getInstance();
        DescribableList<NodeProperty<?>, NodePropertyDescriptor> globalNodeProperties =
                jenkins.getGlobalNodeProperties();
        List<EnvironmentVariablesNodeProperty> envVarsNodePropertyList =
                globalNodeProperties.getAll(hudson.slaves.EnvironmentVariablesNodeProperty.class);

        EnvironmentVariablesNodeProperty newEnvVarsNodeProperty = null;
        EnvVars envVars = null;

        if (envVarsNodePropertyList == null || envVarsNodePropertyList.isEmpty()) {
           newEnvVarsNodeProperty = new hudson.slaves.EnvironmentVariablesNodeProperty();
           globalNodeProperties.add(newEnvVarsNodeProperty);
           envVars = newEnvVarsNodeProperty.getEnvVars();
        } else {
           envVars = envVarsNodePropertyList.get(0).getEnvVars();
        }
        envVars.put(key, value);
        jenkins.save();
     }

    /**
     * Split a string of filenames by newline and remove empty/null entries
     *
     * @param patterns Newline separated list of file patterns
     * @return Array of file patterns
     */
    private String[] splitFiles(String patterns) {
        List<String> newList = new ArrayList<String>();

        String[] patternList = patterns.split("\n");

        for (String pattern : patternList) {
            if (pattern != null && pattern.trim().length() > 0) {
                newList.add(pattern.trim());
            }
        }

        return newList.toArray(new String[newList.size()]);
    }

    /**
     * Set properties on a component version, handling property definitions
     *
     * @param component The name of the component which contains the component version
     * @param version The name of the version on the component to set the properties for
     * @param properties the map of properties to set on the component version
     * @throws AbortException
     */
    private void setComponentVersionProperties(
        String component,
        String version,
        Map<String,String> properties)
    throws AbortException {
        if (!properties.isEmpty()) {
            JSONObject propSheetDef;
            String propSheetDefId;
            String propSheetDefPath;
            JSONArray existingPropDefJsonArray;

            // acquire prop sheet definition and it's existing propDefs
            try {
                propSheetDef = compClient.getComponentVersionPropSheetDef(component);
                propSheetDefId = (String) propSheetDef.get("id");
                propSheetDefPath = (String) propSheetDef.get("path");
                existingPropDefJsonArray = propClient.getPropSheetDefPropDefs(propSheetDefPath);
            }
            catch (IOException ex) {
                throw new AbortException("An error occurred acquiring property sheets: " + ex.getMessage());
            }
            catch (JSONException e) {
                throw new AbortException("An error occurred while processing the JSON object of the version property sheet: " +
                                         e.getMessage());
            }

            // update existing properties
            for (int i = 0; i < existingPropDefJsonArray.length(); i++) {
                JSONObject propDef;
                String propName;

                try {
                    propDef = existingPropDefJsonArray.getJSONObject(i);
                    propName = propDef.getString("name");
                }
                catch (JSONException ex) {
                    throw new AbortException("An error occurred while processing the JSON object of an existing property " +
                                             "definition: " + ex.getMessage());
                }

                String propValue = properties.get(propName);

                if (propValue != null) {
                    try {
                        listener.getLogger().println("Updating version property '" + propName + "' to '" + propValue + "'");
                        verClient.setVersionProperty(version, component, propName, propValue, false);
                        listener.getLogger().println("Successfully updated version property");
                    }
                    catch (IOException ex) {
                        throw new AbortException("An error occurred while updating the property: " + ex.getMessage());
                    }
                }

                properties.remove(propName);
            }

            // create new properties
            if (!properties.isEmpty()) {
                UUID propSheetDefUUID = UUID.fromString(propSheetDefId);

                for (Map.Entry<String, String> property : properties.entrySet()) {
                    String propName = property.getKey();
                    String propDescription = "";
                    String propLabel = "";
                    Boolean required = false;
                    String propType = "TEXT";
                    String propValue = property.getValue();

                    try {
                        listener.getLogger().println("Creating property definition for '" + propName + "'");
                        propClient.createPropDef(propSheetDefUUID,
                                                 propSheetDefPath,
                                                 propName,
                                                 propDescription,
                                                 propLabel,
                                                 required,
                                                 propType,
                                                 "");
                        listener.getLogger().println("Setting version property '" + propName + "' to '" + propValue + "'");
                        verClient.setVersionProperty(version, component, propName, propValue, false);
                        listener.getLogger().println("Successfully set version property");
                    }
                    catch (IOException ex) {
                        throw new AbortException("An error occurred while setting the version property: " + ex.getMessage());
                    }
                    catch (JSONException ex) {
                        throw new AbortException("An error occurred while processing the JSON object for the property: " +
                                                 ex.getMessage());
                    }
                }
            }
        }
    }
}
