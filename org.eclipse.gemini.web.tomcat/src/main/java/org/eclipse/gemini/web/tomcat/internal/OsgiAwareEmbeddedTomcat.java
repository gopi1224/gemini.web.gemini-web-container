/*******************************************************************************
 * Copyright (c) 2009, 2010 VMware Inc.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution. 
 * The Eclipse Public License is available at
 *   http://www.eclipse.org/legal/epl-v10.html
 * and the Apache License v2.0 is available at 
 *   http://www.opensource.org/licenses/apache2.0.php.
 * You may elect to redistribute this code under either of these licenses.  
 *
 * Contributors:
 *   VMware Inc. - initial contribution
 *******************************************************************************/

package org.eclipse.gemini.web.tomcat.internal;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.util.HashMap;

import org.apache.catalina.Authenticator;
import org.apache.catalina.Container;
import org.apache.catalina.Context;
import org.apache.catalina.Engine;
import org.apache.catalina.Globals;
import org.apache.catalina.Host;
import org.apache.catalina.Lifecycle;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.Server;
import org.apache.catalina.Service;
import org.apache.catalina.Valve;
import org.apache.catalina.core.StandardContext;
import org.apache.catalina.startup.Catalina;
import org.apache.catalina.startup.ContextConfig;
import org.apache.tomcat.JarScanner;
import org.apache.tomcat.util.digester.Digester;
import org.apache.tomcat.util.scan.StandardJarScanner;
import org.eclipse.gemini.web.core.spi.ServletContainerException;
import org.eclipse.gemini.web.tomcat.internal.loading.ChainedClassLoader;
import org.eclipse.gemini.web.tomcat.internal.support.BundleFileResolverFactory;
import org.eclipse.gemini.web.tomcat.internal.support.PackageAdminBundleDependencyDeterminer;
import org.eclipse.virgo.util.io.FatalIOException;
import org.eclipse.virgo.util.io.PathReference;
import org.osgi.framework.BundleContext;
import org.osgi.service.packageadmin.PackageAdmin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;

public final class OsgiAwareEmbeddedTomcat extends org.apache.catalina.startup.Tomcat {

    private static final String ROOT_CONTEXT_PATH = "";

    private static final String ROOT_PATH = "/";

    private final static Logger LOGGER = LoggerFactory.getLogger(OsgiAwareEmbeddedTomcat.class);

    private final ExtendCatalina catalina = new ExtendCatalina();

    private final JarScanner jarScanner;

    private final BundleContext bundleContext;

    private File configDir;

    private String defaultContextXml;

    private String defaultWeb;

    private String oldCatalinaBaseDir;

    private String oldCatalinaHomeDir;

    /**
     * Custom mappings of login methods to authenticators
     */
    protected volatile HashMap<String, Authenticator> authenticators;
    
    private final Object monitor = new Object();

    OsgiAwareEmbeddedTomcat(BundleContext context, PackageAdmin packageAdmin) {
        this.bundleContext = context;
        JarScanner bundleDependenciesJarScanner = new BundleDependenciesJarScanner(new PackageAdminBundleDependencyDeterminer(context, packageAdmin),
            BundleFileResolverFactory.createBundleFileResolver());
        JarScanner defaultJarScanner = new StandardJarScanner();

        this.jarScanner = new ChainingJarScanner(bundleDependenciesJarScanner, defaultJarScanner);
    }

    /**
     * Start the server.
     * 
     * @throws LifecycleException
     */
    @Override
    public void start() throws LifecycleException {
        getServer();
        this.server.start();
    }

    public void setServer(Server server) {
        this.server = server;
    }

    @Override
    public Service getService() {
        Server server = getServer();
        Service[] findServices = server.findServices();
        if (findServices != null && findServices.length > 0) {
            return findServices[0];
        }
        throw new IllegalStateException("Unable to locate Service.");
    }

    @Override
    public Host getHost() {
        return findHost();
    }

    @Override
    public Engine getEngine() {
        return findEngine();
    }

    private Engine findEngine() {
        Server server = getServer();
        Service[] findServices = server.findServices();
        for (Service service : findServices) {
            Container container = service.getContainer();
            if (container instanceof Engine) {
                return (Engine) container;
            }
        }
        throw new IllegalStateException("Unable to locate Engine.");
    }

    private Host findHost() {
        Engine engine = findEngine();
        Container[] children = engine.findChildren();
        for (Container container : children) {
            if (container instanceof Host) {
                return (Host) container;
            }
        }
        throw new IllegalStateException("Unable to locate Host.");
    }

    @Override
    public void init() throws LifecycleException {
        getServer();

        enableNaming();
        // TODO introduce property for disabling the naming.
        // Bug 328174 - Provide ability to turn off tomcat naming (jndi) support
        // System.setProperty("catalina.useNaming", "false");

        this.server.init();
    }

    @Override
    public void destroy() throws LifecycleException {
        super.destroy();

        if (oldCatalinaBaseDir != null) {
            System.setProperty(Globals.CATALINA_BASE_PROP, oldCatalinaBaseDir);
        }

        if (oldCatalinaHomeDir != null) {
            System.setProperty(Globals.CATALINA_HOME_PROP, oldCatalinaHomeDir);
        }
    }

    @Override
    public Context addWebapp(String path, String docBase) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Creating context '" + path + "' with docBase '" + docBase + "'");
        }

        StandardContext context = new ExtendedStandardContext();

        ExtendedContextConfig config = new ExtendedContextConfig();

        if (this.configDir == null) {
            // Allocate the tomcat's configuration directory
            this.configDir = TomcatConfigLocator.resolveConfigDir(this.bundleContext);
        }
        config.setConfigBase(this.configDir);

        if (this.defaultWeb == null) {
            // Allocate the default web.xml
            this.defaultWeb = WebappConfigLocator.resolveDefaultWebXml(this.configDir);
        }
        config.setDefaultWebXml(this.defaultWeb);

        // Allocate default context.xml
        if (this.defaultContextXml == null) {
            this.defaultContextXml = WebappConfigLocator.resolveDefaultContextXml(this.configDir);
        }
        config.setDefaultContextXml(this.defaultContextXml);

        // Allocate the web application's configuration directory
        Host host = getHost();
        File configLocation = WebappConfigLocator.resolveWebappConfigDir(this.configDir, host);

        // If web application's context.xml is existing, set it to the StandardContext
        try {
            context.setConfigFile(WebappConfigLocator.resolveWebappContextXml(path, docBase, configLocation));
        } catch (MalformedURLException e) {
            throw new ServletContainerException("Cannot resolve web application's context.xml " + docBase, e);
        }

        context.setDocBase(docBase);
        context.setPath(path.equals(ROOT_PATH) ? ROOT_CONTEXT_PATH : path);

        context.setJarScanner(this.jarScanner);

        context.setParent(host);

        config.setCustomAuthenticators(this.authenticators);
        ((Lifecycle) context).addLifecycleListener(config);

        return context;
    }

    public void configure(InputStream configuration) {
        initBaseDir();

        Digester digester = this.catalina.createStartDigester();
        digester.push(this);

        ClassLoader[] loaders = new ClassLoader[] { Catalina.class.getClassLoader(), getClass().getClassLoader() };
        digester.setClassLoader(ChainedClassLoader.create(loaders));

        try {
            digester.parse(configuration);
        } catch (IOException e) {
            throw new ServletContainerException("Error reading Tomcat configuration file.", e);
        } catch (SAXException e) {
            throw new ServletContainerException("Error parsing Tomcat XML configuration.", e);
        }

        // Allocate the tomcat's configuration directory
        this.configDir = TomcatConfigLocator.resolveConfigDir(this.bundleContext);

        // Allocate default context.xml
        this.defaultContextXml = WebappConfigLocator.resolveDefaultContextXml(this.configDir);

        // Allocate the default web.xml
        this.defaultWeb = WebappConfigLocator.resolveDefaultWebXml(this.configDir);
    }

    @Override
    protected void initBaseDir() {
        if (this.basedir == null) {
            this.basedir = System.getProperty(Globals.CATALINA_BASE_PROP);
        }
        if (this.basedir == null) {
            this.basedir = System.getProperty(Globals.CATALINA_HOME_PROP);
        }
        if (this.basedir == null) {
            // Create a temp dir.
            this.basedir = System.getProperty("user.dir");
            PathReference home = new PathReference(this.basedir);
            home.createDirectory();
            if (!home.isAbsolute()) {
                try {
                    this.basedir = home.getCanonicalPath();
                } catch (FatalIOException e) {
                    this.basedir = home.getAbsolutePath();
                }
            }
        }
        oldCatalinaHomeDir = System.setProperty(Globals.CATALINA_HOME_PROP, this.basedir);
        oldCatalinaBaseDir = System.setProperty(Globals.CATALINA_BASE_PROP, this.basedir);
    }

    /**
     * Maps the specified login method to the specified authenticator, allowing the mappings in
     * org/apache/catalina/startup/Authenticators.properties to be overridden.
     * 
     * @param authenticator Authenticator to handle authentication for the specified login method
     * @param loginMethod Login method that maps to the specified authenticator
     * 
     * @throws IllegalArgumentException if the specified authenticator does not implement the org.apache.catalina.Valve
     *         interface
     */
    public void addAuthenticator(Authenticator authenticator, String loginMethod) {
        if (!(authenticator instanceof Valve)) {
            throw new IllegalArgumentException("Specified Authenticator is not a Valve");
        }
        if (this.authenticators == null) {
            synchronized (monitor) {
                if (this.authenticators == null) {
                    this.authenticators = new HashMap<String, Authenticator>();
                }
            }
        }
        this.authenticators.put(loginMethod, authenticator);
    }

    /**
     * Overrides {@link Catalina} to provide public access to {@link Catalina#createStartDigester}.
     * 
     * 
     */
    private static class ExtendCatalina extends Catalina {

        @Override
        public Digester createStartDigester() {
            return super.createStartDigester();
        }

    }

    /**
     * Extends the Tomcat {@link StandardContext} to add custom functionality.
     * 
     * 
     */
    private static class ExtendedStandardContext extends StandardContext {

        private static final long serialVersionUID = 6914580440115519171L;

        /**
         * Returns <code>true</code> for exploded bundles.
         */
        @Override
        public boolean isFilesystemBased() {
            String docBase = getDocBase();
            File f = new File(docBase);
            return f.isDirectory();
        }

    }

    /**
     * Override {@link ContextConfig}. This changes the {@link ClassLoader} used to load the web-embed.xml to the
     * <code>ClassLoader</code> of this bundle.
     * 
     * 
     */
    private static class ExtendedContextConfig extends ContextConfig {

        private File configDir;

        /**
         * If there is not configuration directory, return custom configuration directory. It is used to resolve the
         * context.xml.default
         */
        @Override
        protected File getConfigBase() {
            File configBase = super.getConfigBase();
            if (configBase != null) {
                return configBase;
            }

            if (this.configDir != null) {
                return this.configDir;
            }

            return null;
        }

        protected void setConfigBase(File configDir) {
            this.configDir = configDir;
        }
    }

}