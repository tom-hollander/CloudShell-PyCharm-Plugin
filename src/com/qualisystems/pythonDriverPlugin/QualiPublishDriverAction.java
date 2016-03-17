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
import com.qualisystems.pythonDriverPlugin.archiveAnalizers.AchiveAnalyzerFactory;
import com.qualisystems.pythonDriverPlugin.archiveAnalizers.IArchiveAnalyzer;
import com.qualisystems.pythonDriverPlugin.deployment.ObjectFactory;
import com.qualisystems.pythonDriverPlugin.deployment.PropertiesType;
import com.qualisystems.pythonDriverPlugin.updaters.IUpdater;
import com.qualisystems.pythonDriverPlugin.updaters.UpdaterFactory;
import org.jetbrains.annotations.NotNull;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import java.io.*;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

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
                            "Failed uploading file:\n Unknown Host",
                            "Publishing on CloudShell");
                    else
                        Messages.showErrorDialog(
                            project,
                            "Failed uploading file:\n" + _exception.toString(),
                            "Publishing on CloudShell");

                    return;
                }

                if (_settings == null) return;

                Messages.showInfoMessage(
                    project,
                    String.format("successfully published items"),
                    "Publishing Python Driver on CloudShell");
            }

            @Override
            public void run(@NotNull ProgressIndicator progressIndicator) {

                try {
                    String basePath = project.getBasePath();
                    _settings = getDeploymentSettingsFromFile(deploymentSettingsFile);

                    IArchiveAnalyzer driversAnalyzer = AchiveAnalyzerFactory.createAnalyzer(_settings.drivers, _settings.fileFilters, basePath);
                    HashMap<String, String> arcivedDriverFiles = driversAnalyzer.getArcivedFiles();

                    IArchiveAnalyzer scriptsAnalyzer = AchiveAnalyzerFactory.createAnalyzer(_settings.scripts, _settings.fileFilters, basePath);
                    HashMap<String, String> arcivedScriptsFiles = scriptsAnalyzer.getArcivedFiles();

                    if(arcivedDriverFiles.size() ==0 && arcivedScriptsFiles.size() ==0)
                    {
                        throw new Exception("no items found for publishing");
                    }

                    IUpdater driversUpdater = UpdaterFactory.createDriversUpdater(_settings);
                    driversUpdater.updateFiles(arcivedDriverFiles);

                    IUpdater scriptsUpdater = UpdaterFactory.createScriptsUpdater(_settings);
                    scriptsUpdater.updateFiles(arcivedScriptsFiles);


                } catch (Exception e) {

                    _exception = e;
                }
            }
        });
    }

    private File zipProjectFolder(String directory, DriverPublisherSettings settings) throws IOException {

        Map<String, ByteBuffer> extras = new HashMap<>();

        if (settings.runFromLocalProject) {

            String debugSettingsFileContent = String.format(DebugSettingsFormatString, directory, Boolean.toString(settings.waitForDebugger));

            extras.put(DebugSettingsFileName, StandardCharsets.UTF_8.encode(debugSettingsFileContent));
        }

        ZipHelper zipHelper = new ZipHelper(extras, settings.fileFilters);

        Path deploymentFilePath = Paths.get(directory, "deployment", settings.driverUniqueName + ".zip");

        if (settings.sourceRootFolder != null && !settings.sourceRootFolder.isEmpty())
            directory = Paths.get(directory, settings.sourceRootFolder).toString();

        zipHelper.zipDir(directory, deploymentFilePath.toString());

        return deploymentFilePath.toFile();
    }

    private DriverPublisherSettings getDeploymentSettingsFromFile(File deploymentSettingsFile) throws IOException {
        JAXBContext jaxbContext;

        try {
            InputStream inputStream = Files.newInputStream(deploymentSettingsFile.toPath());
            jaxbContext = JAXBContext.newInstance(ObjectFactory.class);
            Unmarshaller unmarshaller = jaxbContext.createUnmarshaller();
            JAXBElement<PropertiesType> unmarshalledObject = (JAXBElement<PropertiesType>)unmarshaller.unmarshal(inputStream);

            PropertiesType properties = unmarshalledObject.getValue();
            DriverPublisherSettings settings = DriverPublisherSettings.fromProperties(properties);
            return settings;

        } catch (JAXBException e) {
            throw new IOException(e);
        }
    }
}
