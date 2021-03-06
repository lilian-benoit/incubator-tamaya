/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.tamaya.core.internal;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.osgi.framework.*;
import org.osgi.util.tracker.ServiceTracker;

/**
 * An bundle listener that registers services defined in META-INF/services, when a bundle is starting.
 *
 * @author anatole@apache.org
 */
public class OSGIServiceLoader implements BundleListener {
    // Provide logging
    private static final Logger log = Logger.getLogger(OSGIServiceLoader.class.getName());
    private static final String OSGIRESOURCE_MARKER = "META-INF/OSGIResource";

    private BundleContext context;

    private Map<Class, ServiceTracker<Object,Object>> services = new ConcurrentHashMap<>();

    private Set<Bundle> resourceBundles = Collections.synchronizedSet(new HashSet<Bundle>());

    public OSGIServiceLoader(BundleContext context){
        this.context = Objects.requireNonNull(context);
    }

    public BundleContext getBundleContext(){
        return context;
    }

    public Set<Bundle> getResourceBundles(){
        synchronized (resourceBundles){
            return new HashSet<>(resourceBundles);
        }
    }

    @Override
    public void bundleChanged(BundleEvent bundleEvent) {
        // Parse and create metadata on STARTING
        if (bundleEvent.getType() == BundleEvent.STARTING) {
            Bundle bundle = bundleEvent.getBundle();
            if (bundle.getEntry(OSGIRESOURCE_MARKER) != null) {
                synchronized (resourceBundles){
                    resourceBundles.add(bundle);
                }
            }
            if (bundle.getEntry("META-INF/services/") == null) {
                return;
            }
            Enumeration<String> entryPaths = bundle.getEntryPaths("META-INF/services/");
            while (entryPaths.hasMoreElements()) {
                String entryPath = entryPaths.nextElement();
                if(!entryPath.endsWith("/")) {
                    processEntryPath(bundle, entryPath);
                }
            }
        } else if (bundleEvent.getType() == BundleEvent.STOPPING) {
            Bundle bundle = bundleEvent.getBundle();
            if (bundle.getEntry(OSGIRESOURCE_MARKER) != null) {
                synchronized (resourceBundles){
                    resourceBundles.remove(bundle);
                }
            }
            if (bundle.getEntry("META-INF/services/") == null) {
                return;
            }
            Enumeration<String> entryPaths = bundle.getEntryPaths("META-INF/services/");
            while (entryPaths.hasMoreElements()) {
                String entryPath = entryPaths.nextElement();
                if(!entryPath.endsWith("/")) {
                    removeEntryPath(bundle, entryPath);
                }
            }
        }
    }

    private void processEntryPath(Bundle bundle, String entryPath) {
        try {
            String serviceName = entryPath.substring("META-INF/services/".length());
            Class<?> serviceClass = bundle.loadClass(serviceName);

            URL child = bundle.getEntry(entryPath);
            InputStream inStream = child.openStream();

            BufferedReader br = new BufferedReader(new InputStreamReader(inStream, "UTF-8"));
            String implClassName = br.readLine();
            while (implClassName != null){
                int hashIndex = implClassName.indexOf("#");
                if (hashIndex > 0) {
                    implClassName = implClassName.substring(0, hashIndex-1);
                }
                else if (hashIndex == 0) {
                    implClassName = "";
                }
                implClassName = implClassName.trim();
                if (implClassName.length() > 0) {
                    try {
                        // Load the service class
                        Class<?> implClass = bundle.loadClass(implClassName);
                        if (!serviceClass.isAssignableFrom(implClass)) {
                            log.warning("Configured service: " + implClassName + " is not assignble to " +
                                    serviceClass.getName());
                            continue;
                        }
                        // Provide service properties
                        Hashtable<String, String> props = new Hashtable<>();
                        props.put(Constants.VERSION_ATTRIBUTE, bundle.getVersion().toString());
                        String vendor = bundle.getHeaders().get(Constants.BUNDLE_VENDOR);
                        props.put(Constants.SERVICE_VENDOR, (vendor != null ? vendor : "anonymous"));
                        // Translate annotated @Priority into a service ranking
                        props.put(Constants.SERVICE_RANKING,
                                String.valueOf(PriorityServiceComparator.getPriority(implClass)));

                        // Register the service factory on behalf of the intercepted bundle
                        JDKUtilServiceFactory factory = new JDKUtilServiceFactory(implClass);
                        BundleContext bundleContext = bundle.getBundleContext();
                        bundleContext.registerService(serviceName, factory, props);
                    }
                    catch(Exception e){
                        log.log(Level.SEVERE,
                                "Failed to load service class using ServiceLoader logic: " + implClassName, e);
                    }
                }
                implClassName = br.readLine();
            }
            br.close();
        }
        catch (RuntimeException rte) {
            throw rte;
        }
        catch (Exception e) {
            log.log(Level.SEVERE, "Failed to read services from: " + entryPath, e);
        }
    }

    private void removeEntryPath(Bundle bundle, String entryPath) {
        try {
            String serviceName = entryPath.substring("META-INF/services/".length());
            Class<?> serviceClass = bundle.loadClass(serviceName);

            URL child = bundle.getEntry(entryPath);
            InputStream inStream = child.openStream();

            BufferedReader br = new BufferedReader(new InputStreamReader(inStream, "UTF-8"));
            String implClassName = br.readLine();
            while (implClassName != null){
                int hashIndex = implClassName.indexOf("#");
                if (hashIndex > 0) {
                    implClassName = implClassName.substring(0, hashIndex-1);
                }
                else if (hashIndex == 0) {
                    implClassName = "";
                }
                implClassName = implClassName.trim();
                if (implClassName.length() > 0) {
                    try {
                        // Load the service class
                        Class<?> implClass = bundle.loadClass(implClassName);
                        if (!serviceClass.isAssignableFrom(implClass)) {
                            log.warning("Configured service: " + implClassName + " is not assignble to " +
                                    serviceClass.getName());
                            continue;
                        }
                        ServiceReference<?> ref = bundle.getBundleContext().getServiceReference(implClass);
                        if(ref!=null){
                            bundle.getBundleContext().ungetService(ref);
                        }
                    }
                    catch(Exception e){
                        log.log(Level.SEVERE,
                                "Failed to unload service class using ServiceLoader logic: " + implClassName, e);
                    }
                }
                implClassName = br.readLine();
            }
            br.close();
        }
        catch (RuntimeException rte) {
            throw rte;
        }
        catch (Exception e) {
            log.log(Level.SEVERE, "Failed to read services from: " + entryPath, e);
        }
    }

    /**
     * Service factory simply instantiating the configured service.
     */
    static class JDKUtilServiceFactory implements ServiceFactory
    {
        private final Class<?> serviceClass;

        public JDKUtilServiceFactory(Class<?> serviceClass) {
            this.serviceClass = serviceClass;
        }

        @Override
        public Object getService(Bundle bundle, ServiceRegistration registration) {
            try {
                return serviceClass.newInstance();
            }
            catch (Exception ex) {
                ex.printStackTrace();
                throw new IllegalStateException("Cannot create service: " + serviceClass.getName(), ex);
            }
        }

        @Override
        public void ungetService(Bundle bundle, ServiceRegistration registration, Object service) {
        }
    }
}
