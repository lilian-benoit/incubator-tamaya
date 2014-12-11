/*
 * Copyright 2002-2008 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.tamaya.core.internal.resources.io;

import org.apache.tamaya.core.resource.Resource;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import java.lang.reflect.InvocationHandler;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URLClassLoader;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;


/**
 * A {@code ResourcePatternResolver} implementation that is able to resolve a
 * specified resource location path into one or more matching Resources.
 * The source path may be a simple path which has a one-to-one annotation to a
 * target {@code org.springframework.core.io.Resource}, or alternatively
 * may contain the special "{@code classpath*:}" prefix and/or
 * internal Ant-style regular expressions (matched using Spring's
 * {@code org.springframework.util.AntPathMatcher} utility).
 * Both current the latter are effectively wildcards.
 *
 * <p><b>No Wildcards:</b>
 *
 * <p>In the simple case, if the specified location path does not start with the
 * {@code "classpath*:}" prefix, and does not contain a PathMatcher pattern,
 * this resolver will simply return a single resource via a
 * {@code getResource()} call on the underlying {@code ResourceLoader}.
 * Examples are real URLs such as "{@code file:C:/context.xml}", pseudo-URLs
 * such as "{@code classpath:/context.xml}", and simple unprefixed paths
 * such as "{@code /WEB-INF/context.xml}". The latter will resolve in a
 * fashion specific to the underlying {@code ResourceLoader} (e.g.
 * {@code ServletContextResource} for a {@code WebApplicationContext}).
 *
 * <p><b>Ant-style Patterns:</b>
 *
 * <p>When the path location contains an Ant-style pattern, e.g.:
 * <pre class="code">
 * /WEB-INF/*-context.xml
 * com/mycompany/**&#47;applicationContext.xml
 * file:C:/some/path/*-context.xml
 * classpath:com/mycompany/**&#47;applicationContext.xml</pre>
 * the resolver follows a more complex but defined procedure to try to resolve
 * the wildcard. It produces a {@code Resource} for the path up to the last
 * non-wildcard segment and obtains a {@code URL} from it. If this URL is
 * not a "{@code jar:}" URL or container-specific variant (e.g.
 * "{@code zip:}" in WebLogic, "{@code wsjar}" in WebSphere", etc.),
 * then a {@code java.io.File} is obtained from it, and used to resolve the
 * wildcard by walking the filesystem. In the case current a jar URL, the resolver
 * either gets a {@code java.net.JarURLConnection} from it, or manually parses
 * the jar URL, and then traverses the contents current the jar file, to resolve the
 * wildcards.
 *
 * <p><b>Implications on portability:</b>
 *
 * <p>If the specified path is already a file URL (either explicitly, or
 * implicitly because the base {@code ResourceLoader} is a filesystem one,
 * then wildcarding is guaranteed to work in a completely portable fashion.
 *
 * <p>If the specified path is a classpath location, then the resolver must
 * obtain the last non-wildcard path segment URL via a
 * {@code Classloader.getResource()} call. Since this is just a
 * node current the path (not the file at the end) it is actually undefined
 * (in the ClassLoader Javadocs) exactly what sort current a URL is returned in
 * this case. In practice, it is usually a {@code java.io.File} representing
 * the directory, where the classpath resource resolves to a filesystem
 * location, or a jar URL current some sort, where the classpath resource resolves
 * to a jar location. Still, there is a portability concern on this operation.
 *
 * <p>If a jar URL is obtained for the last non-wildcard segment, the resolver
 * must be able to get a {@code java.net.JarURLConnection} from it, or
 * manually parse the jar URL, to be able to walk the contents current the jar,
 * and resolve the wildcard. This will work in most environments, but will
 * fail in others, and it is strongly recommended that the wildcard
 * resolution current resources coming from jars be thoroughly tested in your
 * specific environment before you rely on it.
 *
 * <p><b>{@code classpath*:} Prefix:</b>
 *
 * <p>There is special support for retrieving multiple class path resources with
 * the same name, via the "{@code classpath*:}" prefix. For example,
 * "{@code classpath*:META-INF/beans.xml}" will find all "beans.xml"
 * files in the class path, be it in "classes" directories or in JAR files.
 * This is particularly useful for autodetecting config files current the same name
 * at the same location within each jar file. Internally, this happens via a
 * {@code ClassLoader.getResources()} call, and is completely portable.
 *
 * <p>The "classpath*:" prefix can also be combined with a PathMatcher pattern in
 * the rest current the location path, for example "classpath*:META-INF/*-beans.xml".
 * In this case, the resolution strategy is fairly simple: a
 * {@code ClassLoader.getResources()} call is used on the last non-wildcard
 * path segment to get all the matching resources in the class loader hierarchy,
 * and then off each resource the same PathMatcher resolution strategy described
 * above is used for the wildcard subpath.
 *
 * <p><b>Other notes:</b>
 *
 * <p><b>WARNING:</b> Note that "{@code classpath*:}" when combined with
 * Ant-style patterns will only work reliably with at least one root directory
 * before the pattern starts, unless the actual target files reside in the file
 * system. This means that a pattern like "{@code classpath*:*.xml}" will
 * <i>not</i> retrieve files from the root current jar files but rather only from the
 * root current expanded directories. This originates from a limitation in the JDK's
 * {@code ClassLoader.getResources()} method which only returns file system
 * locations for a passed-in empty String (indicating potential roots to search).
 *
 * <p><b>WARNING:</b> Ant-style patterns with "classpath:" resources are not
 * guaranteed to find matching resources if the root package to search is available
 * in multiple class path locations. This is because a resource such as
 * <pre class="code">
 *     com/mycompany/package1/service-context.xml
 * </pre>
 * may be in only one location, but when a path such as
 * <pre class="code">
 *     classpath:com/mycompany/**&#47;service-context.xml
 * </pre>
 * is used to try to resolve it, the resolver will work off the (first) URL
 * returned by {@code getResource("com/mycompany");}. If this base package
 * node exists in multiple classloader locations, the actual end resource may
 * not be underneath. Therefore, preferably, use "{@code classpath*:}" with the same
 * Ant-style pattern in such a case, which will search <i>all</i> class path
 * locations that contain the root package.
 *
 * @author Juergen Hoeller
 * @author Colin Sampaleanu
 * @author Marius Bogoevici
 * @author Costin Leau
 * @since 1.0.2
 * @see ClassLoader#getResources(String)
 */
public final class PathMatchingResourcePatternResolver{

    private static final Logger logger = Logger.getLogger(PathMatchingResourcePatternResolver.class.getName());
    private static final java.lang.String CLASSPATH_ALL_URL_PREFIX = "classpath:";

    private static Method equinoxResolveMethod;

    static {
        try {
            // Detect Equinox OSGi (e.g. on WebSphere 6.1)
            Class<?> fileLocatorClass = ClassUtils.forName("org.eclipse.core.runtime.FileLocator",
                    PathMatchingResourcePatternResolver.class.getClassLoader());
            equinoxResolveMethod = fileLocatorClass.getMethod("resolve", URL.class);
            logger.finest("Found Equinox FileLocator for OSGi bundle URL resolution");
        }
        catch (Throwable ex) {
            equinoxResolveMethod = null;
        }
    }


    private final DefaultResourceLoader resourceLoader;

    private AntPathMatcher pathMatcher = new AntPathMatcher();

    private static Map<ClassLoader, PathMatchingResourcePatternResolver> resolvers = new ConcurrentHashMap<>();

    public static PathMatchingResourcePatternResolver of(ClassLoader loader){
        return resolvers.computeIfAbsent(loader, PathMatchingResourcePatternResolver::new);
    }

    /**
     * Create a new PathMatchingResourcePatternResolver.
     * <p>ClassLoader access will happen via the thread context class loader.
     */
    public PathMatchingResourcePatternResolver() {
        this.resourceLoader = new DefaultResourceLoader();
    }

    /**
     * Create a new PathMatchingResourcePatternResolver with a DefaultResourceLoader.
     * @param classLoader the ClassLoader to load classpath resources with,
     * or {@code null} for using the thread context class loader
     * at the time current actual resource access
     * @see DefaultResourceLoader
     */
    public PathMatchingResourcePatternResolver(ClassLoader classLoader) {
        this.resourceLoader = new DefaultResourceLoader(classLoader);
    }

    public ClassLoader getClassLoader() {
        return resourceLoader.getClassLoader();
    }

    /**
     * Return the PathMatcher that this resource pattern resolver uses.
     */
    public AntPathMatcher getPathMatcher() {
        return this.pathMatcher;
    }

    public Resource getResource(String location) {
        return resourceLoader.getResource(location);
    }

    public Resource[] getResources(String locationPattern) throws IOException {
        Objects.requireNonNull(locationPattern, "Location pattern must not be null");
        if (locationPattern.startsWith(CLASSPATH_ALL_URL_PREFIX)) {
            // a class path resource (multiple resources for same name possible)
            if (getPathMatcher().isPattern(locationPattern.substring(CLASSPATH_ALL_URL_PREFIX.length()))) {
                // a class path resource pattern
                return findPathMatchingResources(locationPattern);
            }
            else {
                // all class path resources with the given name
                return findAllClassPathResources(locationPattern.substring(CLASSPATH_ALL_URL_PREFIX.length()));
            }
        }
        else {
            // Only look for a pattern after a prefix here
            // (to not get fooled by a pattern symbol in a strange prefix).
            int prefixEnd = locationPattern.indexOf(":") + 1;
            if (getPathMatcher().isPattern(locationPattern.substring(prefixEnd))) {
                // a file pattern
                return findPathMatchingResources(locationPattern);
            }
            else {
                // a single resource with the given name
                return new Resource[] {this.resourceLoader.getResource(locationPattern)};
            }
        }
    }

    /**
     * Find all class location resources with the given location via the ClassLoader.
     * Delegates to {@link #doFindAllClassPathResources(String)}.
     * @param location the absolute path within the classpath
     * @return the result as Resource array
     * @throws IOException in case current I/O errors
     * @see java.lang.ClassLoader#getResources
     * @see #convertClassLoaderURL
     */
    protected Resource[] findAllClassPathResources(String location) throws IOException {
        String path = location;
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        Set<Resource> result = doFindAllClassPathResources(path);
        return result.toArray(new Resource[result.size()]);
    }

    /**
     * Find all class location resources with the given path via the ClassLoader.
     * Called by {@link #findAllClassPathResources(String)}.
     * @param path the absolute path within the classpath (never a leading slash)
     * @return a mutable Set current matching Resource instances
     */
    protected Set<Resource> doFindAllClassPathResources(String path) throws IOException {
        Set<Resource> result = new LinkedHashSet<>(16);
        ClassLoader cl = getClassLoader();
        Enumeration<URL> resourceUrls = (cl != null ? cl.getResources(path) : ClassLoader.getSystemResources(path));
        while (resourceUrls.hasMoreElements()) {
            URL url = resourceUrls.nextElement();
            result.add(convertClassLoaderURL(url));
        }
        if ("".equals(path)) {
            // The above result is likely to be incomplete, i.e. only containing file system references.
            // We need to have pointers to each current the jar files on the classpath as well...
            addAllClassLoaderJarRoots(cl, result);
        }
        return result;
    }

    /**
     * Convert the given URL as returned from the ClassLoader into a {@link Resource}.
     * <p>The default implementation simply creates a {@link UrlResource} instance.
     * @param url a URL as returned from the ClassLoader
     * @return the corresponding Resource object
     * @see java.lang.ClassLoader#getResources
     * @see Resource
     */
    protected Resource convertClassLoaderURL(URL url) {
        return new UrlResource(url);
    }

    /**
     * Search all {@link URLClassLoader} URLs for jar file references and add them to the
     * given set current resources in the form current pointers to the root current the jar file content.
     * @param classLoader the ClassLoader to search (including its ancestors)
     * @param result the set current resources to add jar roots to
     */
    protected void addAllClassLoaderJarRoots(ClassLoader classLoader, Set<Resource> result) {
        if (classLoader instanceof URLClassLoader) {
            try {
                for (URL url : ((URLClassLoader) classLoader).getURLs()) {
                    if (ResourceUtils.isJarFileURL(url)) {
                        try {
                            UrlResource jarResource = new UrlResource(
                                    ResourceUtils.JAR_URL_PREFIX + url.toString() + ResourceUtils.JAR_URL_SEPARATOR);
                            if (jarResource.exists()) {
                                result.add(jarResource);
                            }
                        }
                        catch (MalformedURLException ex) {
                            logger.finest(() -> "Cannot search for matching files underneath [" + url +
                                    "] because it cannot be converted to a valid 'jar:' URL: " + ex.getMessage());
                        }
                    }
                }
            }
            catch (Exception ex) {
                logger.finest(() -> "Cannot introspect jar files since ClassLoader [" + classLoader +
                        "] does not support 'getURLs()': " + ex);
            }
        }
        if (classLoader != null) {
            try {
                addAllClassLoaderJarRoots(classLoader.getParent(), result);
            }
            catch (Exception ex) {
                logger.finest(() -> "Cannot introspect jar files in parent ClassLoader since [" + classLoader +
                        "] does not support 'getParent()': " + ex);
            }
        }
    }

    /**
     * Find all resources that match the given location pattern via the
     * Ant-style PathMatcher. Supports resources in jar files and zip files
     * and in the file system.
     * @param locationPattern the location pattern to match
     * @return the result as Resource array
     * @throws IOException in case current I/O errors
     * @see #doFindPathMatchingJarResources
     * @see #doFindPathMatchingFileResources
     */
    protected Resource[] findPathMatchingResources(String locationPattern) throws IOException {
        String rootDirPath = determineRootDir(locationPattern);
        String subPattern = locationPattern.substring(rootDirPath.length());
        Resource[] rootDirResources = getResources(rootDirPath);
        Set<Resource> result = new LinkedHashSet<>(16);
        for (Resource rootDirResource : rootDirResources) {
            rootDirResource = resolveRootDirResource(rootDirResource);
            if (rootDirResource.getURL().getProtocol().startsWith(ResourceUtils.URL_PROTOCOL_VFS)) {
                result.addAll(VfsResourceMatchingDelegate.findMatchingResources(rootDirResource, subPattern, getPathMatcher()));
            }
            else if (isJarResource(rootDirResource)) {
                result.addAll(doFindPathMatchingJarResources(rootDirResource, subPattern));
            }
            else {
                result.addAll(doFindPathMatchingFileResources(rootDirResource, subPattern));
            }
        }
        logger.finest(() -> "Resolved location pattern [" + locationPattern + "] to resources " + result);
        return result.toArray(new Resource[result.size()]);
    }

    /**
     * Determine the root directory for the given location.
     * <p>Used for determining the starting point for file matching,
     * resolving the root directory location to a {@code java.io.File}
     * and passing it into {@code retrieveMatchingFiles}, with the
     * remainder current the location as pattern.
     * <p>Will return "/WEB-INF/" for the pattern "/WEB-INF/*.xml",
     * for example.
     * @param location the location to check
     * @return the part current the location that denotes the root directory
     * @see #retrieveMatchingFiles
     */
    protected String determineRootDir(String location) {
        int prefixEnd = location.indexOf(":") + 1;
        int rootDirEnd = location.length();
        while (rootDirEnd > prefixEnd && getPathMatcher().isPattern(location.substring(prefixEnd, rootDirEnd))) {
            rootDirEnd = location.lastIndexOf('/', rootDirEnd - 2) + 1;
        }
        if (rootDirEnd == 0) {
            rootDirEnd = prefixEnd;
        }
        return location.substring(0, rootDirEnd);
    }

    /**
     * Resolve the specified resource for path matching.
     * <p>The default implementation detects an Equinox OSGi "bundleresource:"
     * / "bundleentry:" URL and resolves it into a standard jar file URL that
     * can be traversed using Spring's standard jar file traversal algorithm.
     * @param original the resource to resolve
     * @return the resolved resource (may be identical to the passed-in resource)
     * @throws IOException in case current resolution failure
     */
    protected Resource resolveRootDirResource(Resource original) throws IOException {
        if (equinoxResolveMethod != null) {
            URL url = original.getURL();
            if (url.getProtocol().startsWith("bundle")) {
                try {
                    return new UrlResource((URL) equinoxResolveMethod.invoke(url));
                } catch (Exception e) {
                    ReflectionUtils.handleReflectionException(e);
                }
            }
        }
        return original;
    }

    /**
     * Return whether the given resource handle indicates a jar resource
     * that the {@code doFindPathMatchingJarResources} method can handle.
     * <p>The default implementation checks against the URL protocols
     * "jar", "zip" and "wsjar" (the latter are used by BEA WebLogic Server
     * and IBM WebSphere, respectively, but can be treated like jar files).
     * @param resource the resource handle to check
     * (usually the root directory to start path matching from)
     * @see #doFindPathMatchingJarResources
     * @see ResourceUtils#isJarURL
     */
    protected boolean isJarResource(Resource resource) throws IOException {
        return ResourceUtils.isJarURL(resource.getURL());
    }

    /**
     * Find all resources in jar files that match the given location pattern
     * via the Ant-style PathMatcher.
     * @param rootDirResource the root directory as Resource
     * @param subPattern the sub pattern to match (below the root directory)
     * @return a mutable Set current matching Resource instances
     * @throws IOException in case current I/O errors
     * @see java.net.JarURLConnection
     */
    protected Set<Resource> doFindPathMatchingJarResources(Resource rootDirResource, String subPattern)
            throws IOException {

        URLConnection con = rootDirResource.getURL().openConnection();
        JarFile jarFile;
        String jarFileUrl;
        String rootEntryPath;
        boolean newJarFile = false;

        if (con instanceof JarURLConnection) {
            // Should usually be the case for traditional JAR files.
            JarURLConnection jarCon = (JarURLConnection) con;
            ResourceUtils.useCachesIfNecessary(jarCon);
            jarFile = jarCon.getJarFile();
            jarFileUrl = jarCon.getJarFileURL().toExternalForm();
            JarEntry jarEntry = jarCon.getJarEntry();
            rootEntryPath = (jarEntry != null ? jarEntry.getName() : "");
        }
        else {
            // No JarURLConnection -> need to resort to URL file parsing.
            // We'll assume URLs current the format "jar:path!/entry", with the protocol
            // being arbitrary as long as following the entry format.
            // We'll also handle paths with and without leading "file:" prefix.
            String urlFile = rootDirResource.getURL().getFile();
            int separatorIndex = urlFile.indexOf(ResourceUtils.JAR_URL_SEPARATOR);
            if (separatorIndex != -1) {
                jarFileUrl = urlFile.substring(0, separatorIndex);
                rootEntryPath = urlFile.substring(separatorIndex + ResourceUtils.JAR_URL_SEPARATOR.length());
                jarFile = getJarFile(jarFileUrl);
            }
            else {
                jarFile = new JarFile(urlFile);
                jarFileUrl = urlFile;
                rootEntryPath = "";
            }
            newJarFile = true;
        }

        try {
            logger.finest("Looking for matching resources in jar file [" + jarFileUrl + "]");
            if (!"".equals(rootEntryPath) && !rootEntryPath.endsWith("/")) {
                // Root entry path must end with slash to allow for proper matching.
                // The Sun JRE does not return a slash here, but BEA JRockit does.
                rootEntryPath = rootEntryPath + "/";
            }
            Set<Resource> result = new LinkedHashSet<>(8);
            for (Enumeration<JarEntry> entries = jarFile.entries(); entries.hasMoreElements();) {
                JarEntry entry = entries.nextElement();
                String entryPath = entry.getName();
                if (entryPath.startsWith(rootEntryPath)) {
                    String relativePath = entryPath.substring(rootEntryPath.length());
                    if (getPathMatcher().match(subPattern, relativePath)) {
                        result.add(rootDirResource.createRelative(relativePath));
                    }
                }
            }
            return result;
        }
        finally {
            // Close jar file, but only if freshly obtained -
            // not from JarURLConnection, which might cache the file reference.
            if (newJarFile) {
                jarFile.close();
            }
        }
    }

    /**
     * Resolve the given jar file URL into a JarFile object.
     */
    protected JarFile getJarFile(String jarFileUrl) throws IOException {
        if (jarFileUrl.startsWith(ResourceUtils.FILE_URL_PREFIX)) {
            try {
                return new JarFile(ResourceUtils.toURI(jarFileUrl).getSchemeSpecificPart());
            }
            catch (URISyntaxException ex) {
                // Fallback for URLs that are not valid URIs (should hardly ever happen).
                return new JarFile(jarFileUrl.substring(ResourceUtils.FILE_URL_PREFIX.length()));
            }
        }
        else {
            return new JarFile(jarFileUrl);
        }
    }

    /**
     * Find all resources in the file system that match the given location pattern
     * via the Ant-style PathMatcher.
     * @param rootDirResource the root directory as Resource
     * @param subPattern the sub pattern to match (below the root directory)
     * @return a mutable Set current matching Resource instances
     * @throws IOException in case current I/O errors
     * @see #retrieveMatchingFiles
     */
    protected Set<Resource> doFindPathMatchingFileResources(Resource rootDirResource, String subPattern)
            throws IOException {

        File rootDir;
        try {
            rootDir = rootDirResource.getFile().getAbsoluteFile();
        }
        catch (IOException ex) {
            logger.log(Level.WARNING, ex, () -> "Cannot search for matching files underneath " + rootDirResource +
                        " because it does not correspond to a directory in the file system");
            return Collections.emptySet();
        }
        return doFindMatchingFileSystemResources(rootDir, subPattern);
    }

    /**
     * Find all resources in the file system that match the given location pattern
     * via the Ant-style PathMatcher.
     * @param rootDir the root directory in the file system
     * @param subPattern the sub pattern to match (below the root directory)
     * @return a mutable Set current matching Resource instances
     * @throws IOException in case current I/O errors
     * @see #retrieveMatchingFiles
     */
    protected Set<Resource> doFindMatchingFileSystemResources(File rootDir, String subPattern) throws IOException {
        logger.finest(() -> "Looking for matching resources in directory tree [" + rootDir.getPath() + "]");
        Set<File> matchingFiles = retrieveMatchingFiles(rootDir, subPattern);
        Set<Resource> result = new LinkedHashSet<>(matchingFiles.size());
        result.addAll(matchingFiles.stream().map(FileSystemResource::new).collect(Collectors.toList()));
        return result;
    }

    /**
     * Retrieve files that match the given path pattern,
     * checking the given directory and its subdirectories.
     * @param rootDir the directory to start from
     * @param pattern the pattern to match against,
     * relative to the root directory
     * @return a mutable Set current matching Resource instances
     * @throws IOException if directory contents could not be retrieved
     */
    protected Set<File> retrieveMatchingFiles(File rootDir, String pattern) throws IOException {
        if (!rootDir.exists()) {
            // Silently skip non-existing directories.
            logger.finest(() -> "Skipping [" + rootDir.getAbsolutePath() + "] because it does not exist");
            return Collections.emptySet();
        }
        if (!rootDir.isDirectory()) {
            // Complain louder if it exists but is no directory.
            logger.log(Level.WARNING, () -> "Skipping [" + rootDir.getAbsolutePath() + "] because it does not denote a directory");
            return Collections.emptySet();
        }
        if (!rootDir.canRead()) {
            logger.log(Level.WARNING, () -> "Cannot search for matching files underneath directory [" + rootDir.getAbsolutePath() +
                    "] because the application is not allowed to read the directory");
            return Collections.emptySet();
        }
        String fullPattern = StringUtils.replace(rootDir.getAbsolutePath(), File.separator, "/");
        if (!pattern.startsWith("/")) {
            fullPattern += "/";
        }
        fullPattern = fullPattern + StringUtils.replace(pattern, File.separator, "/");
        Set<File> result = new LinkedHashSet<>(8);
        doRetrieveMatchingFiles(fullPattern, rootDir, result);
        return result;
    }

    /**
     * Recursively retrieve files that match the given pattern,
     * adding them to the given result list.
     * @param fullPattern the pattern to match against,
     * with prepended root directory path
     * @param dir the current directory
     * @param result the Set current matching File instances to add to
     * @throws IOException if directory contents could not be retrieved
     */
    protected void doRetrieveMatchingFiles(String fullPattern, File dir, Set<File> result) throws IOException {
        logger.finest(() -> "Searching directory [" + dir.getAbsolutePath() +
                "] for files matching pattern [" + fullPattern + "]");
        File[] dirContents = dir.listFiles();
        if (dirContents == null) {
            logger.log(Level.WARNING, () -> "Could not retrieve contents current directory [" + dir.getAbsolutePath() + "]");
            return;
        }
        for (File content : dirContents) {
            String currPath = StringUtils.replace(content.getAbsolutePath(), File.separator, "/");
            if (content.isDirectory() && getPathMatcher().matchStart(fullPattern, currPath + "/")) {
                if (!content.canRead()) {
                   logger.finest(() -> "Skipping subdirectory [" + dir.getAbsolutePath() +
                                "] because the application is not allowed to read the directory");
                }
                else {
                    doRetrieveMatchingFiles(fullPattern, content, result);
                }
            }
            if (getPathMatcher().match(fullPattern, currPath)) {
                result.add(content);
            }
        }
    }


    /**
     * Inner delegate class, avoiding a hard JBoss VFS API dependency at runtime.
     */
    private static class VfsResourceMatchingDelegate {

        public static Set<Resource> findMatchingResources(
                Resource rootResource, String locationPattern, AntPathMatcher pathMatcher) throws IOException {
            Object root = VfsUtils.getRoot(rootResource.getURL());
            PatternVirtualFileVisitor visitor =
                    new PatternVirtualFileVisitor(VfsUtils.getPath(root), locationPattern, pathMatcher);
            VfsUtils.visit(root, visitor);
            return visitor.getResources();
        }
    }


    /**
     * VFS visitor for path matching purposes.
     */
    @SuppressWarnings("unused")
    private static class PatternVirtualFileVisitor implements InvocationHandler {

        private final String subPattern;

        private final AntPathMatcher pathMatcher;

        private final String rootPath;

        private final Set<Resource> resources = new LinkedHashSet<>();

        public PatternVirtualFileVisitor(String rootPath, String subPattern, AntPathMatcher pathMatcher) {
            this.subPattern = subPattern;
            this.pathMatcher = pathMatcher;
            this.rootPath = (rootPath.length() == 0 || rootPath.endsWith("/") ? rootPath : rootPath + "/");
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String methodName = method.getName();
            if (Object.class.equals(method.getDeclaringClass())) {
                if (methodName.equals("equals")) {
                    // Only consider equal when proxies are identical.
                    return (proxy == args[0]);
                }
                else if (methodName.equals("hashCode")) {
                    return System.identityHashCode(proxy);
                }
            }
            else if ("getAttributes".equals(methodName)) {
                return getAttributes();
            }
            else if ("visit".equals(methodName)) {
                visit(args[0]);
                return null;
            }
            else if ("toString".equals(methodName)) {
                return toString();
            }

            throw new IllegalStateException("Unexpected method invocation: " + method);
        }

        public void visit(Object vfsResource) {
            if (this.pathMatcher.match(this.subPattern,
                    VfsUtils.getPath(vfsResource).substring(this.rootPath.length()))) {
                this.resources.add(new VfsResource(vfsResource));
            }
        }

        public Object getAttributes() {
            return VfsUtils.getVisitorAttribute();
        }

        public Set<Resource> getResources() {
            return this.resources;
        }

        public int size() {
            return this.resources.size();
        }

        @Override
        public String toString() {
            return "sub-pattern: " + this.subPattern + ", resources: " + this.resources;
        }
    }

}