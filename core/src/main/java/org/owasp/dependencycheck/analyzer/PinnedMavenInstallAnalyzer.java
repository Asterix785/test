/*
 * This file is part of dependency-check-core.
 *
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
 *
 * Copyright (c) 2020 The OWASP Foundation. All Rights Reserved.
 */
package org.owasp.dependencycheck.analyzer;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.github.packageurl.MalformedPackageURLException;
import com.github.packageurl.PackageURL;
import com.github.packageurl.PackageURLBuilder;
import org.owasp.dependencycheck.Engine;
import org.owasp.dependencycheck.analyzer.exception.AnalysisException;
import org.owasp.dependencycheck.data.nvd.ecosystem.Ecosystem;
import org.owasp.dependencycheck.dependency.Confidence;
import org.owasp.dependencycheck.dependency.Dependency;
import org.owasp.dependencycheck.dependency.EvidenceType;
import org.owasp.dependencycheck.dependency.naming.GenericIdentifier;
import org.owasp.dependencycheck.dependency.naming.PurlIdentifier;
import org.owasp.dependencycheck.utils.Settings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.concurrent.ThreadSafe;
import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Used to analyze Maven pinned dependency files named {@code *install*.json}, a
 * Java Maven dependency lockfile like Python's {@code requirements.txt}.
 *
 * @author dhalperi
 * @see
 * <a href="https://github.com/bazelbuild/rules_jvm_external#pinning-artifacts-and-integration-with-bazels-downloader">rules_jvm_external</a>
 */
@Experimental
@ThreadSafe
public class PinnedMavenInstallAnalyzer extends AbstractFileTypeAnalyzer {

    /**
     * The logger.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(PinnedMavenInstallAnalyzer.class);

    /**
     * The name of the analyzer.
     */
    private static final String ANALYZER_NAME = "Pinned Maven install Analyzer";

    /**
     * The phase that this analyzer is intended to run in.
     */
    private static final AnalysisPhase ANALYSIS_PHASE = AnalysisPhase.INFORMATION_COLLECTION;

    /**
     * Pattern matching files with "install" in the basename and extension
     * "json".
     *
     * <p>
     * This regex is designed to explicitly skip files named
     * {@code install.json} since those are used for Cloudflare installations
     * and this will save on work.
     */
    private static final Pattern MAVEN_INSTALL_JSON_PATTERN = Pattern.compile("(.+install.*|.*install.+)\\.json");

    /**
     * Match any files that look like *install*.json.
     */
    private static final FileFilter FILTER = (File file) -> MAVEN_INSTALL_JSON_PATTERN.matcher(file.getName()).matches();

    @Override
    protected FileFilter getFileFilter() {
        return FILTER;
    }

    @Override
    public String getName() {
        return ANALYZER_NAME;
    }

    @Override
    public AnalysisPhase getAnalysisPhase() {
        return ANALYSIS_PHASE;
    }

    @Override
    protected String getAnalyzerEnabledSettingKey() {
        return Settings.KEYS.ANALYZER_MAVEN_INSTALL_ENABLED;
    }

    @Override
    protected void analyzeDependency(Dependency dependency, Engine engine) throws AnalysisException {
        LOGGER.debug("Checking file {}", dependency.getActualFilePath());

        final File dependencyFile = dependency.getActualFile();
        if (!dependencyFile.isFile() || dependencyFile.length() == 0) {
            return;
        }

        final DependencyTree tree;
        try {
            final InstallFile installFile = installFileReader.readValue(dependencyFile);
            tree = installFile.getDependencyTree();
        } catch (IOException e) {
            return;
        }

        if (tree == null) {
            return;
        } else if (!Objects.equals(tree.getAutogeneratedSentinel(), "THERE_IS_NO_DATA_ONLY_ZUUL")) {
            return;
        }

        engine.removeDependency(dependency);

        if (!Objects.equals(tree.getVersion(), "0.1.0")) {
            LOGGER.warn("Unsupported pinned maven_install.json version {}. Continuing optimistically.", tree.getVersion());
        }

        List<MavenDependency> deps = tree.getDependencies();
        if (deps == null) {
            deps = Collections.emptyList();
        }

        for (MavenDependency dep : deps) {
            if (dep.getCoord() == null) {
                LOGGER.warn("Unexpected null coordinate in {}", dependency.getActualFilePath());
                continue;
            }

            LOGGER.debug("Analyzing {}", dep.getCoord());
            final String[] pieces = dep.getCoord().split(":");
            if (pieces.length < 3 || pieces.length > 5) {
                LOGGER.warn("Invalid maven coordinate {}", dep.getCoord());
                continue;
            }

            final String group = pieces[0];
            final String artifact = pieces[1];
            final String version;
            String classifier = null;
            if (pieces.length == 3) {
                version = pieces[2];
            } else if (pieces.length == 4) {
                classifier = pieces[2];
                version = pieces[3];
            } else {
                // length == 5 as guaranteed above.
                classifier = pieces[3];
                version = pieces[4];
            }

            if ("sources".equals(classifier) || "javadoc".equals(classifier)) {
                LOGGER.debug("Skipping sources jar {}", dep.getCoord());
                continue;
            }

            final Dependency d = new Dependency(dependency.getActualFile(), true);
            d.setEcosystem(Ecosystem.JAVA);
            d.addEvidence(EvidenceType.VENDOR, "project", "groupid", group, Confidence.HIGHEST);
            d.addEvidence(EvidenceType.PRODUCT, "project", "artifactid", artifact, Confidence.HIGHEST);
            d.addEvidence(EvidenceType.VERSION, "project", "version", version, Confidence.HIGHEST);
            d.setName(String.format("%s:%s", group, artifact));
            d.setFilePath(String.format("%s>>%s", dependency.getActualFile(), dep.getCoord()));
            d.setFileName(dep.getCoord());
            try {
                final PackageURLBuilder purl = PackageURLBuilder.aPackageURL()
                        .withType(PackageURL.StandardTypes.MAVEN)
                        .withNamespace(group)
                        .withName(artifact)
                        .withVersion(version);
                if (classifier != null) {
                    purl.withQualifier("classifier", classifier);
                }
                d.addSoftwareIdentifier(new PurlIdentifier(purl.build(), Confidence.HIGHEST));
            } catch (MalformedPackageURLException e) {
                d.addSoftwareIdentifier(new GenericIdentifier("maven_install JSON coord " + dep.getCoord(), Confidence.HIGH));
            }
            d.setVersion(version);
            engine.addDependency(d);
        }
    }

    @Override
    protected void prepareFileTypeAnalyzer(Engine engine) {
        // No initialization needed.
    }

    /**
     * Represents the entire pinned Maven dependency set in an install.json
     * file.
     *
     * <p>
     * At the time of writing, the latest version is 0.1.0, and the dependencies
     * are stored in {@code .dependency_tree.dependencies[].coord}.
     *
     * <p>
     * The only top-level key we care about is {@code .dependency_tree}.
     */
    private static class InstallFile {

        /**
         * The dependency tree.
         */
        @JsonProperty("dependency_tree")
        private DependencyTree dependencyTree;

        /**
         * Returns dependencyTree.
         *
         * @return dependencyTree
         */
        public DependencyTree getDependencyTree() {
            return dependencyTree;
        }
    }

    /**
     * Represents the values at {@code .dependency_tree} in the
     * {@link InstallFile install file}.
     */
    private static class DependencyTree {

        /**
         * A sentinel value placed in the file to indicate that it is an
         * auto-generated pinned maven install file.
         */
        @JsonProperty("__AUTOGENERATED_FILE_DO_NOT_MODIFY_THIS_FILE_MANUALLY")
        private String autogeneratedSentinel;

        /**
         * A list of Maven dependencies made available. Note that this list is
         * transitively closed and pinned to a specific version of each
         * artifact.
         */
        @JsonProperty("dependencies")
        private List<MavenDependency> dependencies;

        /**
         * The file format version.
         */
        @JsonProperty("version")
        private String version;

        /**
         * Returns autogeneratedSentinel.
         *
         * @return autogeneratedSentinel
         */
        public String getAutogeneratedSentinel() {
            return autogeneratedSentinel;
        }

        /**
         * Returns dependencies.
         *
         * @return dependencies
         */
        public List<MavenDependency> getDependencies() {
            return dependencies;
        }

        /**
         * Returns version.
         *
         * @return version
         */
        public String getVersion() {
            return version;
        }

    }

    /**
     * Represents a single dependency in the list at
     * {@code .dependency_tree.dependencies}.
     */
    private static class MavenDependency {

        /**
         * The standard Maven coordinate string
         * {@code group:artifact[:optional classifier][:optional packaging]:version}.
         */
        @JsonProperty("coord")
        private String coord;

        /**
         * Returns the value of coord.
         *
         * @return the value of coord
         */
        public String getCoord() {
            return coord;
        }
    }

    @Override
    public void initialize(Settings settings) {
        super.initialize(settings);
        final ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        installFileReader = mapper.readerFor(InstallFile.class);
    }

    /**
     * A reusable reader for {@link InstallFile}.
     */
    private static ObjectReader installFileReader;

}
