/*
 * Copyright (C) 2010 SonarSource SA
 * All rights reserved
 * mailto:contact AT sonarsource DOT com
 */
package com.sonar.plugins.csharp.squid;

import java.util.Collection;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.batch.DependsUpon;
import org.sonar.api.batch.Phase;
import org.sonar.api.batch.ResourceCreationLock;
import org.sonar.api.batch.SensorContext;
import org.sonar.api.measures.CoreMetrics;
import org.sonar.api.measures.PersistenceMode;
import org.sonar.api.measures.RangeDistributionBuilder;
import org.sonar.api.resources.File;
import org.sonar.api.resources.InputFile;
import org.sonar.api.resources.Project;
import org.sonar.plugins.csharp.api.CSharp;
import org.sonar.plugins.csharp.api.CSharpConstants;
import org.sonar.plugins.csharp.api.CSharpResourcesBridge;
import org.sonar.plugins.csharp.api.MicrosoftWindowsEnvironment;
import org.sonar.plugins.csharp.api.sensor.AbstractRegularCSharpSensor;
import org.sonar.squid.Squid;
import org.sonar.squid.api.SourceCode;
import org.sonar.squid.api.SourceFile;
import org.sonar.squid.indexer.QueryByParent;
import org.sonar.squid.indexer.QueryByType;

import com.google.common.collect.Lists;
import com.sonar.csharp.squid.CSharpConfiguration;
import com.sonar.csharp.squid.api.CSharpMetric;
import com.sonar.csharp.squid.api.source.SourceClass;
import com.sonar.csharp.squid.api.source.SourceMember;
import com.sonar.csharp.squid.scanner.CSharpAstScanner;

@DependsUpon(CSharpConstants.CSHARP_CORE_EXECUTED)
@Phase(name = Phase.Name.PRE)
public final class CSharpSquidSensor extends AbstractRegularCSharpSensor {

  private static final Logger LOG = LoggerFactory.getLogger(CSharpSquidSensor.class);
  private static final Number[] METHOD_DISTRIB_BOTTOM_LIMITS = { 1, 2, 4, 6, 8, 10, 12 };
  private static final Number[] CLASS_DISTRIB_BOTTOM_LIMITS = { 0, 5, 10, 20, 30, 60, 90 };
  private CSharp cSharp;
  private CSharpResourcesBridge cSharpResourcesBridge;
  private ResourceCreationLock resourceCreationLock;

  public CSharpSquidSensor(CSharp cSharp, CSharpResourcesBridge cSharpResourcesBridge, ResourceCreationLock resourceCreationLock,
      MicrosoftWindowsEnvironment microsoftWindowsEnvironment) {
    super(microsoftWindowsEnvironment);
    this.cSharp = cSharp;
    this.cSharpResourcesBridge = cSharpResourcesBridge;
    this.resourceCreationLock = resourceCreationLock;
  }

  public void analyse(Project project, SensorContext context) {
    Squid squid = new Squid(createParserConfiguration(project));
    squid.register(CSharpAstScanner.class).scanFiles(getFilesToAnalyse(project));
    squid.decorateSourceCodeTreeWith(CSharpMetric.values());
    saveMeasures(squid, context, project);
  }

  private List<java.io.File> getFilesToAnalyse(Project project) {
    List<java.io.File> result = Lists.newArrayList();
    for (InputFile file : project.getFileSystem().mainFiles(cSharp.getKey())) {
      result.add(file.getFile());
    }
    return result;
  }

  private CSharpConfiguration createParserConfiguration(Project project) {
    return new CSharpConfiguration(project.getFileSystem().getSourceCharset());
  }

  private void saveMeasures(Squid squid, SensorContext context, Project project) {
    // TODO should add real C# metrics, and not bind Namespaces to Packages
    // SourceCode squidProject = squid.getProject();
    // context.saveMeasure(project, CoreMetrics.PACKAGES, squidProject.getDouble(CSharpMetric.NAMESPACES));

    Collection<SourceCode> squidFiles = squid.search(new QueryByType(SourceFile.class));
    for (SourceCode squidFile : squidFiles) {
      File sonarFile = File.fromIOFile(new java.io.File(squidFile.getKey()), project);
      sonarFile.setLanguage(cSharp);
      // Fill the resource bridge API that can be used by other C# plugins to map logical resources to physical ones
      cSharpResourcesBridge.indexFile((SourceFile) squidFile, sonarFile);

      context.saveMeasure(sonarFile, CoreMetrics.CLASSES, squidFile.getDouble(CSharpMetric.CLASSES));
      context.saveMeasure(sonarFile, CoreMetrics.FUNCTIONS, squidFile.getDouble(CSharpMetric.METHODS));
      context.saveMeasure(sonarFile, CoreMetrics.FILES, squidFile.getDouble(CSharpMetric.FILES));
      context.saveMeasure(sonarFile, CoreMetrics.LINES, squidFile.getDouble(CSharpMetric.LINES));
      context.saveMeasure(sonarFile, CoreMetrics.NCLOC, squidFile.getDouble(CSharpMetric.LINES_OF_CODE));
      context.saveMeasure(sonarFile, CoreMetrics.STATEMENTS, squidFile.getDouble(CSharpMetric.STATEMENTS));
      context.saveMeasure(sonarFile, CoreMetrics.ACCESSORS, squidFile.getDouble(CSharpMetric.ACCESSORS));
      context.saveMeasure(sonarFile, CoreMetrics.COMPLEXITY, squidFile.getDouble(CSharpMetric.COMPLEXITY));
      context.saveMeasure(sonarFile, CoreMetrics.COMMENT_BLANK_LINES, squidFile.getDouble(CSharpMetric.COMMENT_BLANK_LINES));
      context.saveMeasure(sonarFile, CoreMetrics.COMMENTED_OUT_CODE_LINES, squidFile.getDouble(CSharpMetric.COMMENTED_OUT_CODE_LINES));
      context.saveMeasure(sonarFile, CoreMetrics.COMMENT_LINES, squidFile.getDouble(CSharpMetric.COMMENT_LINES));
      context.saveMeasure(sonarFile, CoreMetrics.PUBLIC_API, squidFile.getDouble(CSharpMetric.PUBLIC_API));
      context.saveMeasure(sonarFile, CoreMetrics.PUBLIC_UNDOCUMENTED_API,
          squidFile.getDouble(CSharpMetric.PUBLIC_API) - squidFile.getDouble(CSharpMetric.PUBLIC_DOC_API));
      saveClassComplexityDistribution(squidFile, sonarFile, context, squid);
      saveMethodComplexityDistribution(squidFile, sonarFile, context, squid);
    }

    // and lock everything to prevent future modifications
    LOG.debug("Locking the C# Resource Bridge and the Sonar Index: future modifications won't be possible.");
    cSharpResourcesBridge.lock();
    resourceCreationLock.lock();
  }

  private void saveClassComplexityDistribution(SourceCode squidFile, File sonarFile, SensorContext context, Squid squid) {
    Collection<SourceCode> squidClasses = squid.search(new QueryByParent(squidFile), new QueryByType(SourceClass.class));
    RangeDistributionBuilder complexityClassDistribution = new RangeDistributionBuilder(CoreMetrics.CLASS_COMPLEXITY_DISTRIBUTION,
        CLASS_DISTRIB_BOTTOM_LIMITS);
    for (SourceCode squidClass : squidClasses) {
      complexityClassDistribution.add(squidClass.getDouble(CSharpMetric.COMPLEXITY));
    }
    context.saveMeasure(sonarFile, complexityClassDistribution.build().setPersistenceMode(PersistenceMode.MEMORY));
  }

  private void saveMethodComplexityDistribution(SourceCode squidFile, File sonarFile, SensorContext context, Squid squid) {
    Collection<SourceCode> squidMethods = squid.search(new QueryByParent(squidFile), new QueryByType(SourceMember.class));
    RangeDistributionBuilder complexityMethodDistribution = new RangeDistributionBuilder(CoreMetrics.FUNCTION_COMPLEXITY_DISTRIBUTION,
        METHOD_DISTRIB_BOTTOM_LIMITS);
    for (SourceCode squidMethod : squidMethods) {
      complexityMethodDistribution.add(squidMethod.getDouble(CSharpMetric.COMPLEXITY));
    }
    context.saveMeasure(sonarFile, complexityMethodDistribution.build().setPersistenceMode(PersistenceMode.MEMORY));
  }
}
