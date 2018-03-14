package aspectj

import org.gradle.api.tasks.SourceSet

public interface NamingConventions {

    String getClassesTaskName(SourceSet sourceSet);

    String getAspectCompileTaskName(SourceSet sourceSet);

    String getAspectPathConfigurationName(SourceSet sourceSet);

    String getAspectInpathConfigurationName(SourceSet sourceSet);
    
    String getRuntimeConfigurationName(SourceSet sourceSet);

    String getArchivePrefix(SourceSet sourceSet);

}