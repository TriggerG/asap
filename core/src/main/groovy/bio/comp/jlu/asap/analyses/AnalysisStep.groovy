
package bio.comp.jlu.asap.analyses


import groovy.util.logging.Slf4j
import java.nio.file.Path
import bio.comp.jlu.asap.api.RunningStates
import bio.comp.jlu.asap.api.AnalysesSteps
import bio.comp.jlu.asap.Step

import static bio.comp.jlu.asap.api.MiscConstants.*
import static bio.comp.jlu.asap.api.Paths.*
import static bio.comp.jlu.asap.api.RunningStates.*


/**
 *
 * @author Oliver Schwengers <oliver.schwengers@computational.bio.uni-giessen.de>
 */
@Slf4j
abstract class AnalysisStep extends Step {


    AnalysisStep( String stepName, def config, boolean localMode ) {

        super( stepName, config, localMode )

        config.analyses[ stepName ] = [
            status: INIT.toString()
        ]

    }


    @Override
    boolean isSelected() {

        return true

    }


    public boolean hasStepFinished( AnalysesSteps step ) {

        return config?.analyses[ step.getAbbreviation() ]?.status == FINISHED.toString()

    }


    @Override
    public void setStatus( RunningStates status ) {

        config.analyses[ stepName ].status = status.toString()

    }


    @Override
    public RunningStates getStatus() {

        return RunningStates.getEnum( config.analyses[ stepName ].status )

    }


    @Override
    void run() {

        log.trace( "${stepName} running..." )
        try {

            if( check() ) {

                setStatus( SETUP )
                setup()

                setStatus( RUNNING )
                config.analyses[ stepName ].start = (new Date()).format( DATE_FORMAT )
                runStep()

                clean()
                config.analyses[ stepName ].end = (new Date()).format( DATE_FORMAT )
                setStatus( FINISHED )
                success = true

            } else {
                log.warn( "skip ${stepName} analysis step upon failed check!" )
                success = false
                setStatus( SKIPPED )
            }

        } catch( Throwable ex ) {
            log.error( "${stepName} analysis step aborted!", ex )
            success = false
            setStatus( FAILED )
            config.analyses[ stepName ].error = ex.getLocalizedMessage()
        }

    }

}

