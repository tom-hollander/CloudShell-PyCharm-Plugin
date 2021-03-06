package com.qualisystems.pythonDriverPlugin;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public class QualiPublishDriverAction extends AnAction {

    public static final String DeploymentSettingsFileName = "deployment.xml";
    public static final String DebugSettingsFileName = "debug.xml";

    public static final String DebugSettingsFormatString =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n" +
            "<properties>\n" +
            "<entry key=\"loadFrom\">%s</entry>\n" +
            "<entry key=\"waitForDebugger\">%s</entry>\n" +
            "</properties>\n";

    @Override
    public void actionPerformed(AnActionEvent anActionEvent) {

        final Project project = anActionEvent.getData(CommonDataKeys.PROJECT);

        if (project == null) return;

        FileDocumentManager.getInstance().saveAllDocuments();

        final File deploymentSettingsFile = new File(project.getBasePath(), DeploymentSettingsFileName);

        if (!deploymentSettingsFile.exists()) {

            Messages.showErrorDialog(
                project,
                String.format("Could not find %s in the project folder, cannot upload driver.", DeploymentSettingsFileName),
                "Missing Deployment Configuration File");

            return;
        }

        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Publishing Python Driver on CloudShell") {

            public Exception _exception;
            public DriverPublisherSettings _settings;

            @Override
            public void onSuccess() {

                if (_exception != null) {

                    _exception.printStackTrace();

                    if (_exception instanceof UnknownHostException)
                        Messages.showErrorDialog(
                            project,
                            "Failed uploading new driver file:\n Unknown Host",
                            "Publishing Python Driver on CloudShell");
                    else
                        Messages.showErrorDialog(
                            project,
                            "Failed uploading new driver file:\n" + _exception.toString(),
                            "Publishing Python Driver on CloudShell");

                    return;
                }

                if (_settings == null) return;

                Messages.showInfoMessage(
                    project,
                    String.format("Successfully uploaded new driver file for driver `%s`", _settings.driverUniqueName),
                    "Publishing Python Driver on CloudShell");
            }

            @Override
            public void run(@NotNull ProgressIndicator progressIndicator) {

                try {

                    _settings = getDeploymentSettingsFromFile(deploymentSettingsFile);

                    File zippedProjectFile = zipProjectFolder(project.getBasePath(), _settings);

                    ResourceManagementService resourceManagementService =
                        ResourceManagementService.OpenConnection(_settings.serverRootAddress, _settings.port, _settings.username, _settings.password, _settings.domain);

                    resourceManagementService.updateDriver(_settings.driverUniqueName, zippedProjectFile);

                } catch (Exception e) {

                    _exception = e;
                }
            }
        });
    }

    private File zipProjectFolder(String directory, DriverPublisherSettings settings) throws Exception {

        Map<String, ByteBuffer> extras = new HashMap<>();

        if (settings.sourceRootFolder != null && !settings.sourceRootFolder.isEmpty()) {

            Path sourceFolder = Paths.get(directory, settings.sourceRootFolder);

            if (!Files.exists(sourceFolder))
                throw new Exception(String.format("Couldn't find specified source folder \"%s\" as in \"%s\"", settings.sourceRootFolder, sourceFolder.toString()));

            directory = sourceFolder.toString();
        }

        if (settings.runFromLocalProject) {

            String debugSettingsFileContent = String.format(DebugSettingsFormatString, directory, Boolean.toString(settings.waitForDebugger));

            extras.put(DebugSettingsFileName, StandardCharsets.UTF_8.encode(debugSettingsFileContent));
        }

        ZipHelper zipHelper = new ZipHelper(extras, settings.fileFilters);

        Path deploymentFilePath = Paths.get(directory, "deployment", settings.driverUniqueName + ".zip");

        zipHelper.zipDir(directory, deploymentFilePath.toString());

        return deploymentFilePath.toFile();
    }

    private DriverPublisherSettings getDeploymentSettingsFromFile(File deploymentSettingsFile) throws IOException {

        Properties properties = new Properties();

        properties.loadFromXML(Files.newInputStream(deploymentSettingsFile.toPath()));

        DriverPublisherSettings settings = DriverPublisherSettings.fromProperties(properties);

        return settings;
    }
}
