/*
 * Copyright 2020 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.maven.utilities;

import org.openrewrite.ExecutionContext;
import org.openrewrite.SourceFile;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.marker.JavaProject;
import org.openrewrite.java.marker.JavaSourceSet;
import org.openrewrite.java.marker.JavaVersion;
import org.openrewrite.marker.BuildTool;
import org.openrewrite.marker.GitProvenance;
import org.openrewrite.marker.Marker;
import org.openrewrite.maven.MavenParser;
import org.openrewrite.maven.tree.Maven;
import org.openrewrite.maven.tree.Pom;
import org.openrewrite.maven.tree.Scope;
import org.openrewrite.properties.PropertiesParser;
import org.openrewrite.xml.XmlParser;
import org.openrewrite.yaml.YamlParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.openrewrite.Tree.randomId;

/**
 * Parse a Maven project on disk into a list of {@link org.openrewrite.SourceFile} including
 * Maven, Java, YAML, properties, and XML AST representations of sources and resources found.
 */
public class MavenProjectParser {

    private static final Pattern mavenWrapperVersionPattern = Pattern.compile(".*apache-maven/(.*?)/.*");
    private static final Logger logger = LoggerFactory.getLogger(MavenProjectParser.class);

    private final MavenParser mavenParser;
    private final MavenArtifactDownloader artifactDownloader;
    private final JavaParser.Builder<?, ?> javaParserBuilder;
    private final ExecutionContext ctx;

    public MavenProjectParser(MavenArtifactDownloader artifactDownloader,
                              MavenParser.Builder mavenParserBuilder,
                              JavaParser.Builder<?, ?> javaParserBuilder,
                              ExecutionContext ctx) {
        this.mavenParser = mavenParserBuilder.build();
        this.artifactDownloader = artifactDownloader;
        this.javaParserBuilder = javaParserBuilder;
        this.ctx = ctx;
    }

    public List<SourceFile> parse(Path projectDirectory) {
        GitProvenance gitProvenance = GitProvenance.fromProjectDirectory(projectDirectory);
        List<Maven> mavens = mavenParser.parse(Maven.getMavenPoms(projectDirectory, ctx), projectDirectory, ctx);
        List<SourceFile> sourceFiles = new ArrayList<>(mavens);

        mavens = sort(mavens);

        JavaParser javaParser = javaParserBuilder
                .build();

        logger.info("The order in which projects are being parsed is:");
        for (Maven maven : mavens) {
            logger.info("  {}:{}", maven.getModel().getGroupId(), maven.getModel().getArtifactId());
        }

        for (Maven maven : mavens) {
            List<Marker> projectProvenance = getJavaProvenance(maven, projectDirectory);

            List<Path> dependencies = downloadArtifacts(maven.getModel().getDependencies(Scope.Compile));
            JavaSourceSet mainProvenance = JavaSourceSet.build("main", dependencies, ctx);
            javaParser.setClasspath(dependencies);
            sourceFiles.addAll(ListUtils.map(javaParser.parse(maven.getJavaSources(projectDirectory, ctx), projectDirectory, ctx),
                    addProvenance(projectProvenance, mainProvenance)));

            List<Path> testDependencies = downloadArtifacts(maven.getModel().getDependencies(Scope.Test));
            JavaSourceSet testProvenance = JavaSourceSet.build("test", testDependencies, ctx);
            javaParser.setClasspath(testDependencies);
            sourceFiles.addAll(ListUtils.map(javaParser.parse(maven.getTestJavaSources(projectDirectory, ctx), projectDirectory, ctx),
                    addProvenance(projectProvenance, testProvenance)));

            parseResources(maven.getResources(projectDirectory, ctx), projectDirectory, sourceFiles, projectProvenance, mainProvenance);
            parseResources(maven.getTestResources(projectDirectory, ctx), projectDirectory, sourceFiles, projectProvenance, testProvenance);
        }

        return ListUtils.map(sourceFiles, s -> s.withMarkers(s.getMarkers().addIfAbsent(gitProvenance)));
    }

    private List<Marker> getJavaProvenance(Maven maven, Path projectDirectory) {
        Pom mavenModel = maven.getModel();
        String javaRuntimeVersion = System.getProperty("java.runtime.version");
        String javaVendor = System.getProperty("java.vm.vendor");
        String sourceCompatibility = javaRuntimeVersion;
        String targetCompatibility = javaRuntimeVersion;
        String propertiesSourceCompatibility = mavenModel.getValue(mavenModel.getValue("maven.compiler.source"));
        if (propertiesSourceCompatibility != null) {
            sourceCompatibility = propertiesSourceCompatibility;
        }
        String propertiesTargetCompatibility = mavenModel.getValue(mavenModel.getValue("maven.compiler.target"));
        if (propertiesTargetCompatibility != null) {
            targetCompatibility = propertiesTargetCompatibility;
        }

        Path wrapperPropertiesPath = projectDirectory.resolve(".mvn/wrapper/maven-wrapper.properties");
        String mavenVersion = "3.6";
        if (Files.exists(wrapperPropertiesPath)) {
            try {
                Properties wrapperProperties = new Properties();
                wrapperProperties.load(new FileReader(wrapperPropertiesPath.toFile()));
                String distributionUrl = (String) wrapperProperties.get("distributionUrl");
                if (distributionUrl != null) {
                    Matcher wrapperVersionMatcher = mavenWrapperVersionPattern.matcher(distributionUrl);
                    if (wrapperVersionMatcher.matches()) {
                        mavenVersion = wrapperVersionMatcher.group(1);
                    }
                }
            } catch (IOException e) {
                ctx.getOnError().accept(e);
            }
        }

        return Arrays.asList(
                new BuildTool(randomId(), BuildTool.Type.Maven, mavenVersion),
                new JavaVersion(randomId(), javaRuntimeVersion, javaVendor, sourceCompatibility, targetCompatibility),
                new JavaProject(randomId(), mavenModel.getName(), new JavaProject.Publication(
                        mavenModel.getGroupId(),
                        mavenModel.getArtifactId(),
                        mavenModel.getVersion()
                ))
        );
    }

    private void parseResources(List<Path> resources, Path projectDirectory, List<SourceFile> sourceFiles, List<Marker> projectProvenance, JavaSourceSet sourceSet) {
        sourceFiles.addAll(ListUtils.map(new XmlParser().parse(
                resources.stream()
                        .filter(p -> p.getFileName().toString().endsWith(".xml"))
                        .collect(Collectors.toList()),
                projectDirectory,
                ctx
        ), addProvenance(projectProvenance, sourceSet)));

        sourceFiles.addAll(ListUtils.map(new YamlParser().parse(
                resources.stream()
                        .filter(p -> p.getFileName().toString().endsWith(".yml") || p.getFileName().toString().endsWith(".yaml"))
                        .collect(Collectors.toList()),
                projectDirectory,
                ctx
        ), addProvenance(projectProvenance, sourceSet)));

        sourceFiles.addAll(ListUtils.map(new PropertiesParser().parse(
                resources.stream()
                        .filter(p -> p.getFileName().toString().endsWith(".properties"))
                        .collect(Collectors.toList()),
                projectDirectory,
                ctx
        ), addProvenance(projectProvenance, sourceSet)));
    }

    private <S extends SourceFile> UnaryOperator<S> addProvenance(List<Marker> projectProvenance, JavaSourceSet sourceSet) {
        return s -> {
            for (Marker marker : projectProvenance) {
                s = s.withMarkers(s.getMarkers().addIfAbsent(marker));
            }
            s = s.withMarkers(s.getMarkers().addIfAbsent(sourceSet));
            return s;
        };
    }

    private List<Path> downloadArtifacts(Set<Pom.Dependency> dependencies) {
        return dependencies.stream()
                .filter(d -> d.getRepository() != null)
                .map(artifactDownloader::downloadArtifact)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    public static List<Maven> sort(List<Maven> mavens) {
        // the value is the set of maven projects that depend on the key
        Map<Maven, Set<Maven>> byDependedOn = new HashMap<>();

        for (Maven maven : mavens) {
            byDependedOn.computeIfAbsent(maven, m -> new HashSet<>());
            for (Pom.Dependency dependency : maven.getModel().getDependencies()) {
                for (Maven test : mavens) {
                    if (test.getModel().getGroupId().equals(dependency.getGroupId()) &&
                            test.getModel().getArtifactId().equals(dependency.getArtifactId())) {
                        byDependedOn.computeIfAbsent(maven, m -> new HashSet<>()).add(test);
                    }
                }
            }
        }

        List<Maven> sorted = new ArrayList<>(mavens.size());
        next:
        while (!byDependedOn.isEmpty()) {
            for (Map.Entry<Maven, Set<Maven>> mavenAndDependencies : byDependedOn.entrySet()) {
                if (mavenAndDependencies.getValue().isEmpty()) {
                    Maven maven = mavenAndDependencies.getKey();
                    byDependedOn.remove(maven);
                    sorted.add(maven);
                    for (Set<Maven> dependencies : byDependedOn.values()) {
                        dependencies.remove(maven);
                    }
                    continue next;
                }
            }
        }

        return sorted;
    }
}
