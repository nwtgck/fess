/*
 * Copyright 2012-2019 CodeLibs Project and the Others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package org.codelibs.fess.helper;

import static org.codelibs.core.stream.StreamUtil.split;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.ServletContext;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.codelibs.core.io.CopyUtil;
import org.codelibs.core.lang.StringUtil;
import org.codelibs.curl.Curl;
import org.codelibs.curl.CurlResponse;
import org.codelibs.fess.crawler.Constants;
import org.codelibs.fess.exception.PluginException;
import org.codelibs.fess.util.ComponentUtil;
import org.codelibs.fess.util.ResourceUtil;
import org.lastaflute.di.exception.IORuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

public class PluginHelper {
    private static final Logger logger = LoggerFactory.getLogger(PluginHelper.class);

    protected LoadingCache<ArtifactType, Artifact[]> availableArtifacts = CacheBuilder.newBuilder().maximumSize(10)
            .expireAfterWrite(5, TimeUnit.MINUTES).build(new CacheLoader<ArtifactType, Artifact[]>() {
                public Artifact[] load(ArtifactType key) {
                    final List<Artifact> list = new ArrayList<>();
                    for (final String url : getRepositories()) {
                        list.addAll(processRepository(key, url));
                    }
                    return list.toArray(new Artifact[list.size()]);
                }
            });

    public Artifact[] getAvailableArtifacts(final ArtifactType artifactType) {
        try {
            return availableArtifacts.get(artifactType);
        } catch (final Exception e) {
            throw new PluginException("Failed to access " + artifactType, e);
        }
    }

    protected String[] getRepositories() {
        return split(ComponentUtil.getFessConfig().getPluginRepositories(), ",").get(
                stream -> stream.map(s -> s.trim()).toArray(n -> new String[n]));
    }

    protected List<Artifact> processRepository(final ArtifactType artifactType, final String url) {
        final List<Artifact> list = new ArrayList<>();
        final String repoContent = getRepositoryContent(url);
        final Matcher matcher = Pattern.compile("href=\"[^\"]*(" + artifactType.getId() + "[a-zA-Z0-9\\-]+)/?\"").matcher(repoContent);
        while (matcher.find()) {
            final String name = matcher.group(1);
            final String pluginUrl = url + (url.endsWith("/") ? name + "/" : "/" + name + "/");
            final String pluginMetaContent = getRepositoryContent(pluginUrl + "maven-metadata.xml");
            try (final InputStream is = new ByteArrayInputStream(pluginMetaContent.getBytes(Constants.UTF_8_CHARSET))) {
                final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                final DocumentBuilder builder = factory.newDocumentBuilder();
                final Document document = builder.parse(is);
                final NodeList nodeList = document.getElementsByTagName("version");
                for (int i = 0; i < nodeList.getLength(); i++) {
                    final String version = nodeList.item(i).getTextContent();
                    if (isTargetPluginVersion(version)) {
                        if (version.endsWith("SNAPSHOT")) {
                            final String snapshotVersion = getSnapshotActualVersion(builder, pluginUrl, version);
                            if (StringUtil.isNotBlank(snapshotVersion)) {
                                String actualVersion = version.replace("SNAPSHOT", snapshotVersion);
                                list.add(new Artifact(name, actualVersion, pluginUrl + version + "/" + name + "-" + actualVersion + ".jar"));
                            } else if (logger.isDebugEnabled()) {
                                logger.debug("Snapshot name is not found: " + name + "/" + version);
                            }
                        } else {
                            list.add(new Artifact(name, version, pluginUrl + version + "/" + name + "-" + version + ".jar"));
                        }
                    } else if (logger.isDebugEnabled()) {
                        logger.debug(name + ":" + version + " is ignored.");
                    }
                }
            } catch (final Exception e) {
                logger.warn("Failed to parse " + pluginUrl + "maven-metadata.xml.", e);
            }
        }
        return list;
    }

    protected boolean isTargetPluginVersion(final String version) {
        return ComponentUtil.getFessConfig().isTargetPluginVersion(version);
    }

    protected String getSnapshotActualVersion(final DocumentBuilder builder, final String pluginUrl, final String version)
            throws SAXException, IOException {
        String timestamp = null;
        String buildNumber = null;
        final String versionMetaContent = getRepositoryContent(pluginUrl + version + "/maven-metadata.xml");
        try (final InputStream is = new ByteArrayInputStream(versionMetaContent.getBytes(Constants.UTF_8_CHARSET))) {
            final Document doc = builder.parse(is);
            final NodeList snapshotNodeList = doc.getElementsByTagName("snapshot");
            if (snapshotNodeList.getLength() > 0) {
                NodeList nodeList = snapshotNodeList.item(0).getChildNodes();
                for (int i = 0; i < nodeList.getLength(); i++) {
                    final Node node = nodeList.item(i);
                    if ("timestamp".equalsIgnoreCase(node.getNodeName())) {
                        timestamp = node.getTextContent();
                    } else if ("buildNumber".equalsIgnoreCase(node.getNodeName())) {
                        buildNumber = node.getTextContent();
                    }
                }
            }
        }
        if (StringUtil.isNotBlank(timestamp) && StringUtil.isNotBlank(buildNumber)) {
            return timestamp + "-" + buildNumber;
        }
        return null;
    }

    protected String getRepositoryContent(final String url) {
        if (logger.isDebugEnabled()) {
            logger.debug("Loading " + url);
        }
        try (final CurlResponse response = Curl.get(url).execute()) {
            return response.getContentAsString();
        } catch (final IOException e) {
            throw new IORuntimeException(e);
        }
    }

    public Artifact[] getInstalledArtifacts(final ArtifactType artifactType) {
        final File[] jarFiles = ResourceUtil.getPluginJarFiles(artifactType.getId());
        final List<Artifact> list = new ArrayList<>(jarFiles.length);
        for (final File file : jarFiles) {
            list.add(getArtifactFromFileName(artifactType, file.getName()));
        }
        list.sort((a, b) -> a.getName().compareTo(b.getName()));
        return list.toArray(new Artifact[list.size()]);
    }

    public static Artifact getArtifactFromFileName(final ArtifactType artifactType, final String fileName) {
        final String convertedFileName = fileName.substring(artifactType.getId().length() + 1, fileName.lastIndexOf('.'));
        final int firstIndexOfDash = convertedFileName.indexOf('-');
        final String artifactName = artifactType.getId() + "-" + convertedFileName.substring(0, firstIndexOfDash);
        final String artifactVersion = convertedFileName.substring(firstIndexOfDash + 1);
        return new Artifact(artifactName, artifactVersion, null);
    }

    public static ArtifactType getArtifactTypeFromFileName(final String filename) {
        return ArtifactType.getType(new Artifact(filename, null, null));
    }

    public void installArtifact(final Artifact artifact) {
        final String fileName = artifact.getFileName();
        try (final CurlResponse response = Curl.get(artifact.getUrl()).execute()) {
            if (response.getHttpStatusCode() != 200) {
                throw new PluginException("HTTP Status " + response.getHttpStatusCode() + " : failed to get the artifact from "
                        + artifact.getUrl());
            }
            try (final InputStream in = response.getContentAsStream()) {
                CopyUtil.copy(in, ResourceUtil.getPluginPath(fileName).toFile());
            }
        } catch (final Exception e) {
            throw new PluginException("Failed to install the artifact " + artifact.getName(), e);
        }
    }

    public void deleteInstalledArtifact(final Artifact artifact) {
        final String fileName = artifact.getFileName();
        final Path jarPath = Paths.get(getPluginPath().toString(), fileName);
        if (!Files.exists(jarPath)) {
            throw new PluginException(fileName + " does not exist.");
        }
        try {
            Files.delete(jarPath);
        } catch (final IOException e) {
            throw new PluginException("Failed to delete the artifact " + fileName, e);
        }
    }

    protected Path getPluginPath() {
        return Paths.get(ComponentUtil.getComponent(ServletContext.class).getRealPath("/WEB-INF/plugin"));
    }

    public static class Artifact {
        protected final String name;
        protected final String version;
        protected final String url;

        public Artifact(final String name, final String version, final String url) {
            this.name = name;
            this.version = version;
            this.url = url;
        }

        public String getName() {
            return name;
        }

        public String getVersion() {
            return version;
        }

        public String getFileName() {
            return name + "-" + version + ".jar";
        }

        public String getUrl() {
            return url;
        }

        @Override
        public String toString() {
            return "Artifact [name=" + name + ", version=" + version + "]";
        }
    }

    public enum ArtifactType {
        DATA_STORE("fess-ds"), UNKNOWN("unknown");

        private final String id;

        private ArtifactType(final String id) {
            this.id = id;
        }

        public String getId() {
            return id;
        }

        public static ArtifactType getType(final Artifact artifact) {
            if (artifact.getName().startsWith(DATA_STORE.getId())) {
                return DATA_STORE;
            }
            return UNKNOWN;
        }
    }
}
