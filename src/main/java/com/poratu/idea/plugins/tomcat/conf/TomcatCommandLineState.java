package com.poratu.idea.plugins.tomcat.conf;

import com.intellij.debugger.settings.DebuggerSettings;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.Executor;
import com.intellij.execution.configurations.JavaCommandLineState;
import com.intellij.execution.configurations.JavaParameters;
import com.intellij.execution.configurations.ParametersList;
import com.intellij.execution.process.KillableProcessHandler;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.poratu.idea.plugins.tomcat.utils.PluginUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathFactory;
import java.io.File;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Author : zengkid
 * Date   : 2017-02-17
 * Time   : 11:10 AM
 */

public class TomcatCommandLineState extends JavaCommandLineState {

    private static final String TOMCAT_MAIN_CLASS = "org.apache.catalina.startup.Bootstrap";
    private static final String PARAM_CATALINA_HOME = "catalina.home";
    private static final String PARAM_CATALINA_BASE = "catalina.base";
    private static final String PARAM_LOGGING_CONFIG = "java.util.logging.config.file";
    private static final String PARAM_LOGGING_MANAGER = "java.util.logging.manager";
    private static final String PARAM_LOGGING_MANAGER_VALUE = "org.apache.juli.ClassLoaderLogManager";
    private TomcatRunConfiguration configuration;

    protected TomcatCommandLineState(@NotNull ExecutionEnvironment environment) {
        super(environment);
    }

    protected TomcatCommandLineState(ExecutionEnvironment environment, TomcatRunConfiguration configuration) {
        this(environment);
        this.configuration = configuration;
    }

    @Override
    @NotNull
    protected OSProcessHandler startProcess() throws ExecutionException {
        OSProcessHandler progressHandler = super.startProcess();
        if (progressHandler instanceof KillableProcessHandler) {
            boolean shouldKillSoftly = !DebuggerSettings.getInstance().KILL_PROCESS_IMMEDIATELY;
            ((KillableProcessHandler) progressHandler).setShouldKillProcessSoftly(shouldKillSoftly);
        }
        return progressHandler;
    }

    @Override
    protected JavaParameters createJavaParameters() {
        try {
            Path workingPath = PluginUtils.getWorkingPath(configuration);
            Module module = configuration.getModule();
            if (workingPath == null || module == null) {
                throw new ExecutionException("The Module Root specified is not a module according to Intellij");
            }

            Path tomcatInstallationPath = Paths.get(configuration.getTomcatInfo().getPath());
            Project project = this.configuration.getProject();
            String contextPath = configuration.getContextPath();
            String tomcatVersion = configuration.getTomcatInfo().getVersion();
            String vmOptions = configuration.getVmOptions();
            Map<String, String> envOptions = configuration.getEnvOptions();

            // copy the Tomcat configuration files to the working directory
            Path confPath = workingPath.resolve("conf");
            Files.createDirectories(confPath);
            FileUtil.copyFileOrDir(tomcatInstallationPath.resolve("conf").toFile(), confPath.toFile());
            updateServerConf(tomcatVersion, module, confPath, contextPath, configuration);

            ProjectRootManager manager = ProjectRootManager.getInstance(project);

            JavaParameters javaParams = new JavaParameters();
            javaParams.setWorkingDirectory(workingPath.toFile());
            javaParams.setJdk(manager.getProjectSdk());
            javaParams.setDefaultCharset(project);
            javaParams.setMainClass(TOMCAT_MAIN_CLASS);
            javaParams.getProgramParametersList().add("start");
            addBinFolder(tomcatInstallationPath, javaParams);
            addLibFolder(tomcatInstallationPath, javaParams);

            javaParams.setPassParentEnvs(configuration.getPassParentEnvironmentVariables());
            if (envOptions != null) {
                javaParams.setEnv(envOptions);
            }

            ParametersList vmParams = javaParams.getVMParametersList();
            vmParams.addParametersString(vmOptions);
            vmParams.defineProperty(PARAM_CATALINA_HOME, workingPath.toString());
            vmParams.defineProperty(PARAM_CATALINA_BASE, workingPath.toString());
            vmParams.defineProperty(PARAM_LOGGING_CONFIG, confPath.resolve("logging.properties").toString());
            vmParams.defineProperty(PARAM_LOGGING_MANAGER, PARAM_LOGGING_MANAGER_VALUE);

            return javaParams;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }

    @Nullable
    @Override
    protected ConsoleView createConsole(@NotNull Executor executor) {
        return new ServerConsoleView(configuration);
    }

    private void updateServerConf(String tomcatVersion, Module module, Path confPath, String contextPath, TomcatRunConfiguration cfg) throws Exception {
        Path serverXml = confPath.resolve("server.xml");

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(serverXml.toUri().toString());
        XPathFactory xPathfactory = XPathFactory.newInstance();
        XPath xpath = xPathfactory.newXPath();
        XPathExpression exprConnectorShutdown = xpath.compile("/Server[@shutdown='SHUTDOWN']");
        XPathExpression exprConnector = xpath.compile("/Server/Service[@name='Catalina']/Connector[@protocol='HTTP/1.1']");
        XPathExpression expr = xpath.compile("/Server/Service[@name='Catalina']/Engine[@name='Catalina']/Host");
        XPathExpression exprContext = xpath.compile
                ("/Server/Service[@name='Catalina']/Engine[@name='Catalina']/Host/Context");

        Element portShutdown = (Element) exprConnectorShutdown.evaluate(doc, XPathConstants.NODE);
        Element portE = (Element) exprConnector.evaluate(doc, XPathConstants.NODE);
        Node hostNode = (Node) expr.evaluate(doc, XPathConstants.NODE);
        NodeList nodeList = (NodeList) exprContext.evaluate(doc, XPathConstants.NODESET);

        if (nodeList != null && nodeList.getLength() > 0) {
            for (int i = 0; i < nodeList.getLength(); i++) {
                Node node = nodeList.item(i);
                node.getParentNode().removeChild(node);
            }
        }
        portShutdown.setAttribute("port", cfg.getAdminPort());
        portE.setAttribute("port", cfg.getPort());

        Element contextE = doc.createElement("Context");
        String customContext = cfg.getDocBase() + "/META-INF/context_local.xml";
        File customContextFile = new File(customContext);
        if (customContextFile.exists()) {
            org.w3c.dom.Document customContextDoc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(customContextFile);
            contextE = (Element) doc.importNode(customContextDoc.getDocumentElement(), true);
        } else {
            customContext = cfg.getDocBase() + "/META-INF/context.xml";
            customContextFile = new File(customContext);
            if (customContextFile.exists()) {
                org.w3c.dom.Document customContextDoc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(customContextFile);
                contextE = (Element) doc.importNode(customContextDoc.getDocumentElement(), true);
            }
        }

        contextE.setAttribute("docBase", cfg.getDocBase());
        contextE.setAttribute("path", (contextPath.startsWith("/") ? "" : "/") + contextPath);
        hostNode.appendChild(contextE);

        List<String> paths = new ArrayList<>();
        VirtualFile[] classPaths = ModuleRootManager.getInstance(module).orderEntries().withoutSdk().runtimeOnly().productionOnly().getClassesRoots();
        if (classPaths.length > 0) {
            for (VirtualFile path : classPaths) {
                String classPath = path.getPresentableUrl();
                paths.add(classPath);
            }
            int index = tomcatVersion.indexOf(".");
            int version = Integer.parseInt(tomcatVersion.substring(0, index));

            if (version >= 8) { //for tomcat8

                Element resourcesE = doc.createElement("Resources");
                contextE.appendChild(resourcesE);
                for (String classPath : paths) {
                    File file = Paths.get(classPath).toFile();

                    if (file.isFile()) {
                        Element postResourcesE = doc.createElement("PostResources");

                        postResourcesE.setAttribute("base", classPath);
                        postResourcesE.setAttribute("className", "org.apache.catalina.webresources.FileResourceSet");
                        postResourcesE.setAttribute("webAppMount", "/WEB-INF/lib/" + file.getName());
                        resourcesE.appendChild(postResourcesE);

                    } else {
                        Element preResourcesE = doc.createElement("PreResources");
                        preResourcesE.setAttribute("base", classPath);
                        preResourcesE.setAttribute("className", "org.apache.catalina.webresources.DirResourceSet");
                        preResourcesE.setAttribute("webAppMount", "/WEB-INF/classes");
                        resourcesE.appendChild(preResourcesE);
                    }

                }
            } else if (version >= 6) { //for tomcat6-7
                Element loaderE = doc.createElement("Loader");
                loaderE.setAttribute("className", "org.apache.catalina.loader.VirtualWebappLoader");
                loaderE.setAttribute("virtualClasspath", String.join(";", paths));
                contextE.appendChild(loaderE);
            }
        }

        Source source = new DOMSource(doc);
        StreamResult result = new StreamResult(new OutputStreamWriter(Files.newOutputStream(serverXml),
                StandardCharsets.UTF_8));
        Transformer xformer = TransformerFactory.newInstance().newTransformer();
        xformer.transform(source, result);
    }

    private void addBinFolder(Path tomcatInstallation, JavaParameters javaParams) throws ExecutionException {
        // Dynamically adds the tomcat jars to the classpath
        Path binFolder = tomcatInstallation.resolve("bin");
        if (!Files.exists(binFolder)) {
            throw new ExecutionException("The Tomcat installation configured doesn't contains a bin folder");
        }
        String[] jars = binFolder.toFile().list((dir, name) -> name.endsWith(".jar"));

        assert jars != null;
        for (String jarFile : jars) {
            javaParams.getClassPath().add(binFolder.resolve(jarFile).toFile().getAbsolutePath());
        }
    }

    private void addLibFolder(Path tomcatInstallation, JavaParameters javaParams) throws ExecutionException {
        // add libs folder
        Path libFolder = tomcatInstallation.resolve("lib");
        if (!Files.exists(libFolder)) {
            throw new ExecutionException("The Tomcat installation configured doesn't contains a lib folder");
        }
        String[] jars = libFolder.toFile().list((dir, name) -> name.endsWith(".jar"));

        assert jars != null;
        for (String jarFile : jars) {
            javaParams.getClassPath().add(libFolder.resolve(jarFile).toFile().getAbsolutePath());
        }
    }

}
