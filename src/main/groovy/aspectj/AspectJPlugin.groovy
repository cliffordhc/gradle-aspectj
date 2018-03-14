package aspectj

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.logging.LogLevel
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.TaskAction
import org.gradle.api.file.SourceDirectorySet;
import org.gradle.api.internal.file.SourceDirectorySetFactory;
import org.gradle.api.internal.plugins.DslObject;
import javax.inject.Inject;

/**
 *
 * @author Luke Taylor
 * @author Mike Noordermeer
 */
class AspectJPlugin implements Plugin<Project> {
    public static final String ASPECTJ_CONFIGURATION_NAME = "aspectj";
    def SourceDirectorySetFactory sourceDirectorySetFactory;

    @Inject
    AspectJPlugin(SourceDirectorySetFactory sourceDirectorySetFactory) {
        this.sourceDirectorySetFactory = sourceDirectorySetFactory;
    }

    void apply(Project project) {
        project.plugins.apply(JavaPlugin)

        def aspectj = project.extensions.create('aspectj', AspectJExtension, project)

        if (project.configurations.findByName('ajtools') == null) {
            project.configurations.create('ajtools')
            project.afterEvaluate { p ->
                if (aspectj.version == null) {
                    throw new GradleException("No aspectj version supplied")
                }

                p.dependencies {
                    ajtools "org.aspectj:aspectjtools:${aspectj.version}"
                    compile "org.aspectj:aspectjrt:${aspectj.version}"
                }
            }
        }
        
        for (projectSourceSet in project.sourceSets) {
            def namingConventions = projectSourceSet.name.equals('main') ? new MainNamingConventions() : new DefaultNamingConventions();
            for (configuration in [namingConventions.getAspectPathConfigurationName(projectSourceSet), namingConventions.getAspectInpathConfigurationName(projectSourceSet)]) {
                if (project.configurations.findByName(configuration) == null) {
                    project.configurations.create(configuration)
                }
            }
            // for each source set we will:
            // Add a new 'aspectj' virtual directory mapping
            def AspectJSourceDirPluginConvention aspectjDirectoryDelegate = new AspectJSourceDirPluginConvention(projectSourceSet.displayName, sourceDirectorySetFactory);
            new DslObject(projectSourceSet).convention.plugins.put(
                AspectJSourceDirPluginConvention.NAME, aspectjDirectoryDelegate);
            def srcDir = "src/"+ projectSourceSet.name +"/aspectj";
            aspectjDirectoryDelegate.aspectj.srcDir(srcDir);
            projectSourceSet.allSource.source(aspectjDirectoryDelegate.aspectj);

            // create an AspectjTask for this sourceSet following the gradle
            // naming conventions via call to sourceSet.getTaskName()
            // Set up the Aspectj output directory (adding to javac inputs!)
            def outputDirectory = project.buildDir.toPath()
            .resolve("generated")
            .resolve("aspectj")
            .resolve(projectSourceSet.name).toFile();
 
            aspectjDirectoryDelegate.aspectj.outputDir = outputDirectory;
            
            def aspectTaskName = namingConventions.getAspectCompileTaskName(projectSourceSet)
            def classesTaskName = namingConventions.getClassesTaskName(projectSourceSet)
            
            project.tasks.create(name: aspectTaskName, overwrite: true, description: "Compiles AspectJ Source for ${projectSourceSet.name} source set", type: Ajc) {
                sourceSet = projectSourceSet
                inputs.files(sourceSet.aspectj)
                outputs.dir(sourceSet.aspectj.outputDir)
                aspectpath = project.configurations.findByName(namingConventions.getAspectPathConfigurationName(projectSourceSet))
                ajInpath = project.configurations.findByName(namingConventions.getAspectInpathConfigurationName(projectSourceSet))
            }

            // Add out directory as first item to runtimeClasspath
            // TODO: Investigate and confirm that the new folder add will always overwrite existing directory if there are duplicates
            // 1) Internet search did provide clear answer
            // 2) Gradle source shows the use of LinkedHashSet that should preserve order
            // *) Ask on gradle forums
            
            project.sourceSets."${projectSourceSet.name}".output.dir outputDirectory          
            
            // register fact that aspectj should be run before compiling
            project.tasks[aspectTaskName].setDependsOn(project.tasks[classesTaskName].dependsOn)
            project.tasks[aspectTaskName].dependsOn(project.tasks[aspectTaskName].aspectpath)
            project.tasks[aspectTaskName].dependsOn(project.tasks[aspectTaskName].ajInpath)
            project.tasks[classesTaskName].dependsOn(project.tasks[aspectTaskName])
        }
    }

    private static class MainNamingConventions implements NamingConventions {

        @Override
        String getClassesTaskName(final SourceSet sourceSet) {
            return "classes"
        }

        @Override
        String getAspectCompileTaskName(final SourceSet sourceSet) {
            return "compileAspect"
        }

        @Override
        String getAspectPathConfigurationName(final SourceSet sourceSet) {
            return "aspectpath"
        }

        @Override
        String getAspectInpathConfigurationName(final SourceSet sourceSet) {
            return "ajInpath"
        }
        
        @Override
        String getRuntimeConfigurationName(SourceSet sourceSet){
            return "runtime"
        }
        
        @Override
        String getArchivePrefix(SourceSet sourceSet){
            return "aspectj"
        }
    }

    private static class DefaultNamingConventions implements NamingConventions {

        @Override
        String getClassesTaskName(final SourceSet sourceSet) {
            return "${sourceSet.name}Classes"
        }

        @Override
        String getAspectCompileTaskName(final SourceSet sourceSet) {
            return "compile${sourceSet.name.capitalize()}Aspect"
        }

        @Override
        String getAspectPathConfigurationName(final SourceSet sourceSet) {
            return "${sourceSet.name}Aspectpath"
        }

        @Override
        String getAspectInpathConfigurationName(final SourceSet sourceSet) {
            return "${sourceSet.name}AjInpath"
        }
        
        @Override
        String getRuntimeConfigurationName(SourceSet sourceSet){
            return "${sourceSet.name}Runtime"
        }
        
        @Override
        String getArchivePrefix(SourceSet sourceSet){
            return "${sourceSet.name}-aspectj"
        }
    }
}

class Ajc extends DefaultTask {

    SourceSet sourceSet

    FileCollection aspectpath
    FileCollection ajInpath

    // ignore or warning
    String xlint = 'ignore'

    String maxmem
    Map<String, String> additionalAjcArgs

    Ajc() {
        logging.captureStandardOutput(LogLevel.INFO)
    }

    @TaskAction
    def compile() {
        if(sourceSet.aspectj.srcDirs.any({it.exists()})) {
            logger.info("=" * 30)
            logger.info("=" * 30)
            logger.info("Running ajc ...")
            logger.info("classpath: ${sourceSet.compileClasspath.asPath}")
            logger.info("srcDirs $sourceSet.aspectj.srcDirs")
        
            def iajcArgs = [
                classpath           : sourceSet.compileClasspath.asPath,
                destDir             : sourceSet.aspectj.outputDir.absolutePath,
                s                   : sourceSet.aspectj.outputDir.absolutePath,
                source              : project.convention.plugins.java.sourceCompatibility,
                target              : project.convention.plugins.java.targetCompatibility,
                inpath              : (ajInpath + sourceSet.output).asPath,
                xlint               : xlint,
                fork                : 'true',
                aspectPath          : aspectpath.asPath,
                sourceRootCopyFilter: '**/*.java,**/*.aj',
                showWeaveInfo       : 'true']

            if (null != maxmem) {
                iajcArgs['maxmem'] = maxmem
            }

            if (null != additionalAjcArgs) {
                for (pair in additionalAjcArgs) {
                    iajcArgs[pair.key] = pair.value
                }
            }

            ant.taskdef(resource: "org/aspectj/tools/ant/taskdefs/aspectjTaskdefs.properties", classpath: project.configurations.ajtools.asPath)
            ant.iajc(iajcArgs) {
                sourceRoots {
                    sourceSet.aspectj.srcDirs.each {
                        logger.info("   sourceRoot $it")
                        pathelement(location: it.absolutePath)
                    }
                }
            }
        }
    }
}

class AspectJExtension {

    String version

    AspectJExtension(Project project) {
        this.version = project.findProperty('aspectjVersion') ?: '1.8.12'
    }
}

class AspectJSourceDirPluginConvention {
    
    public static final NAME = "aspectj"
    
    SourceDirectorySet aspectj
  
    AspectJSourceDirPluginConvention(String parentDisplayName, SourceDirectorySetFactory sourceDirectorySetFactory) {
        def displayName = parentDisplayName + " Aspectj source";
        aspectj = sourceDirectorySetFactory.create(displayName);
        aspectj.filter.include("**/*.aj");
    }

    def aspectj(Closure closure) {
        closure.delegate = this
        closure()
    }
}