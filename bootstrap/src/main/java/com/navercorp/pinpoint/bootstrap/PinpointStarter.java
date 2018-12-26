/*
 * Copyright 2014 NAVER Corp.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.navercorp.pinpoint.bootstrap;

import com.navercorp.pinpoint.ProductInfo;
import com.navercorp.pinpoint.bootstrap.agentdir.AgentDirectory;
import com.navercorp.pinpoint.bootstrap.classloader.PinpointClassLoaderFactory;
import com.navercorp.pinpoint.bootstrap.classloader.ProfilerLibs;
import com.navercorp.pinpoint.bootstrap.config.DefaultProfilerConfig;
import com.navercorp.pinpoint.bootstrap.config.ProfilerConfig;
import com.navercorp.pinpoint.common.Version;
import com.navercorp.pinpoint.common.util.PinpointThreadFactory;
import com.navercorp.pinpoint.common.util.SimpleProperty;
import com.navercorp.pinpoint.common.util.StringUtils;
import com.navercorp.pinpoint.common.util.SystemProperty;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * @author Jongho Moon
 */
class PinpointStarter {

    private final BootLogger logger = BootLogger.getLogger(PinpointStarter.class.getName());

    public static final String AGENT_TYPE = "AGENT_TYPE";

    public static final String DEFAULT_AGENT = "DEFAULT_AGENT";
    public static final String BOOT_CLASS = "com.navercorp.pinpoint.profiler.DefaultAgent";

    public static final String PLUGIN_TEST_AGENT = "PLUGIN_TEST";
    public static final String PLUGIN_TEST_BOOT_CLASS = "com.navercorp.pinpoint.test.PluginTestAgent";

    private SimpleProperty systemProperty = SystemProperty.INSTANCE;

    private final Map<String, String> agentArgs;
    private final AgentDirectory agentDirectory;
    private final Instrumentation instrumentation;
    private final ClassLoader parentClassLoader;
    private final ModuleBootLoader moduleBootLoader;


    public PinpointStarter(ClassLoader parentClassLoader, Map<String, String> agentArgs,
                           AgentDirectory agentDirectory,
                           Instrumentation instrumentation, ModuleBootLoader moduleBootLoader) {
        //        null == BootstrapClassLoader
//        if (bootstrapClassLoader == null) {
//            throw new NullPointerException("bootstrapClassLoader must not be null");
//        }
        if (agentArgs == null) {
            throw new NullPointerException("agentArgs must not be null");
        }
        if (agentDirectory == null) {
            throw new NullPointerException("agentDirectory must not be null");
        }
        if (instrumentation == null) {
            throw new NullPointerException("instrumentation must not be null");
        }
        this.agentArgs = agentArgs;
        this.parentClassLoader = parentClassLoader;
        this.agentDirectory = agentDirectory;
        this.instrumentation = instrumentation;
        this.moduleBootLoader = moduleBootLoader;

    }


    boolean start() {


        final ContainerResolver containerResolver = new ContainerResolver();
        final boolean isContainer = containerResolver.isContainer();

        List<String> pluginJars = agentDirectory.getPlugins();//
        String configPath = getConfigPath(agentDirectory);//todo 得到config文件
        if (configPath == null) {
            return false;
        }

        // set the path of log file as a system property
        saveLogFilePath(agentDirectory);//todo 设置日志保存地址

        savePinpointVersion();

        try {
            // Is it right to load the configuration in the bootstrap?
            ProfilerConfig profilerConfig = DefaultProfilerConfig.load(configPath); //todo 加载配置文件中的配置到环境变量

            final IdValidator idValidator = new IdValidator();

            String applicationName = idValidator.getApplicationName(profilerConfig);
            if (applicationName == null || applicationName.isEmpty()) {
                logger.warn("applicationName not define");
                return false;
            }

            final String agentId = idValidator.getAgentId(applicationName);
            if (agentId == null) {
                return false;
            }


            // this is the library list that must be loaded
            final URL[] urls = resolveLib(agentDirectory);
            final ClassLoader agentClassLoader = createClassLoader("pinpoint.agent", urls, parentClassLoader);//
            if (moduleBootLoader != null) {
                this.logger.info("defineAgentModule");
                moduleBootLoader.defineAgentModule(agentClassLoader, urls);
            }

            final String bootClass = getBootClass();
            AgentBootLoader agentBootLoader = new AgentBootLoader(bootClass, urls, agentClassLoader);
            logger.info("pinpoint agent [" + bootClass + "] starting...");//todo 真正的启动类 bootclass=com.navercorp.pinpoint.profiler.DefaultAgent
            //todo 得到一个AgentOption对象，包含了instrument对象、启动bootstrap的jar包地址plugin的jar包地址，总之所有的我们配置的数据这里面都有
            AgentOption option = createAgentOption(agentId, applicationName, isContainer, profilerConfig, instrumentation, pluginJars, agentDirectory);
            Agent pinpointAgent = agentBootLoader.boot(option);
            pinpointAgent.start();
            registerShutdownHook(pinpointAgent);
            logger.info("pinpoint agent started normally.");
        } catch (Exception e) {
            // unexpected exception that did not be checked above
            logger.warn(ProductInfo.NAME + " start failed.", e);
            return false;
        }
        return true;
    }

    private ClassLoader createClassLoader(final String name, final URL[] urls, final ClassLoader parentClassLoader) {
        if (System.getSecurityManager() != null) {
            return AccessController.doPrivileged(new PrivilegedAction<ClassLoader>() {
                public ClassLoader run() {
                    return PinpointClassLoaderFactory.createClassLoader(name, urls, parentClassLoader, ProfilerLibs.PINPOINT_PROFILER_CLASS);
                }
            });
        } else {
            return PinpointClassLoaderFactory.createClassLoader(name, urls, parentClassLoader, ProfilerLibs.PINPOINT_PROFILER_CLASS);
        }
    }

    private String getBootClass() {
        final String agentType = getAgentType().toUpperCase();
        if (PLUGIN_TEST_AGENT.equals(agentType)) {
            return PLUGIN_TEST_BOOT_CLASS;
        }
        return BOOT_CLASS;
    }

    private String getAgentType() {
        String agentType = agentArgs.get(AGENT_TYPE);
        if (agentType == null) {
            return DEFAULT_AGENT;
        }
        return agentType;

    }

    private AgentOption createAgentOption(final String agentId, final String applicationName, boolean isContainer,
                                          ProfilerConfig profilerConfig,
                                          Instrumentation instrumentation,
                                          List<String> pluginJars,
                                          AgentDirectory agentDirectory) {
        List<String> bootstrapJarPaths = agentDirectory.getBootDir().toList();//todo 得到boot文件夹下的jar
        return new DefaultAgentOption(instrumentation, agentId, applicationName, isContainer, profilerConfig, pluginJars, bootstrapJarPaths);
    }

    // for test
    void setSystemProperty(SimpleProperty systemProperty) {
        this.systemProperty = systemProperty;
    }

    private void registerShutdownHook(final Agent pinpointAgent) {
        final Runnable stop = new Runnable() {
            @Override
            public void run() {
                pinpointAgent.stop();
            }
        };
        PinpointThreadFactory pinpointThreadFactory = new PinpointThreadFactory("Pinpoint-shutdown-hook", false);
        Thread thread = pinpointThreadFactory.newThread(stop);
        Runtime.getRuntime().addShutdownHook(thread);
    }


    private void saveLogFilePath(AgentDirectory agentDirectory) {
        String agentLogFilePath = agentDirectory.getAgentLogFilePath();
        logger.info("logPath:" + agentLogFilePath);

        systemProperty.setProperty(ProductInfo.NAME + ".log", agentLogFilePath);
    }

    private void savePinpointVersion() {
        logger.info("pinpoint version:" + Version.VERSION);
        systemProperty.setProperty(ProductInfo.NAME + ".version", Version.VERSION);
    }

    private String getConfigPath(AgentDirectory agentDirectory) {
        final String configName = ProductInfo.NAME + ".config";
        String pinpointConfigFormSystemProperty = systemProperty.getProperty(configName);
        if (pinpointConfigFormSystemProperty != null) {
            logger.info(configName + " systemProperty found. " + pinpointConfigFormSystemProperty);
            return pinpointConfigFormSystemProperty;
        }

        String classPathAgentConfigPath;
        if (!StringUtils.isEmpty(System.getProperty("isLocal"))) {
            classPathAgentConfigPath = agentDirectory.getAgentConfigPath();
        } else {
            //针对七鱼做的特殊改造，将配置置于项目中，启动时根据当前的执行路径向上找1个父节点以后，再向下遍历，直到找到名为pinpoint.config的文件为止
            classPathAgentConfigPath = searchPinpointConfig();
        }

        if (classPathAgentConfigPath != null) {
            logger.info("classpath " + configName + " found. " + classPathAgentConfigPath);
            return classPathAgentConfigPath;
        }

        logger.info(configName + " file not found.");
        return null;
    }

    private String searchPinpointConfig() {

        File userDir = new File(System.getProperty("user.dir"));

        File pinpoingConfig = dfsPinpointConfig(userDir.getParentFile());
        return pinpoingConfig == null ? null : pinpoingConfig.getAbsolutePath();
    }

    private static File dfsPinpointConfig(File file) {
        final String configName = ProductInfo.NAME + ".config";

        if (file == null)
            return null;

        if (file.isFile()) {
            if (file.getName().equals(configName))
                return file;
            else
                return null;
        }

        File[] childs = file.listFiles();
        if (childs == null)
            return null;

        for (File child : childs) {
            File pinpointConfig = dfsPinpointConfig(child);
            if (pinpointConfig != null)
                return pinpointConfig;
        }
        return null;
    }


    private URL[] resolveLib(AgentDirectory classPathResolver) {
        // this method may handle only absolute path,  need to handle relative path (./..agentlib/lib)
        String agentJarFullPath = classPathResolver.getAgentJarFullPath();
        String agentLibPath = classPathResolver.getAgentLibPath();
        List<URL> urlList = resolveLib(classPathResolver.getLibs());
        String agentConfigPath = classPathResolver.getAgentConfigPath();

        if (logger.isInfoEnabled()) {
            logger.info("agent JarPath:" + agentJarFullPath);
            logger.info("agent LibDir:" + agentLibPath);
            for (URL url : urlList) {
                logger.info("agent Lib:" + url);
            }
            logger.info("agent config:" + agentConfigPath);
        }
        return urlList.toArray(new URL[0]);
    }

    private List<URL> resolveLib(List<URL> urlList) {
        if (DEFAULT_AGENT.equalsIgnoreCase(getAgentType())) {
            final List<URL> releaseLib = new ArrayList<URL>(urlList.size());
            for (URL url : urlList) {
                //
                if (!url.toExternalForm().contains("pinpoint-profiler-test")) {
                    releaseLib.add(url);
                }
            }
            return releaseLib;
        } else {
            logger.info("load " + PLUGIN_TEST_AGENT + " lib");
            // plugin test
            return urlList;
        }
    }

}
