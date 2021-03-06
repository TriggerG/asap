
package bio.comp.jlu.asap

import java.io.IOException
import java.nio.file.*
import java.util.concurrent.*
import groovy.json.JsonSlurper
import groovy.util.CliBuilder
import ch.qos.logback.classic.*
import org.slf4j.LoggerFactory
import bio.comp.jlu.asap.api.*
import bio.comp.jlu.asap.analyses.AnalysesRunner
import bio.comp.jlu.asap.genomes.GenomeRunner
import bio.comp.jlu.asap.reports.ReportRunner
import bio.comp.jlu.asap.steps.*

import static bio.comp.jlu.asap.api.AnalysesSteps.*
import static bio.comp.jlu.asap.api.Paths.*
import static bio.comp.jlu.asap.api.MiscConstants.*
import static bio.comp.jlu.asap.api.RunningStates.*
import static bio.comp.jlu.asap.api.GenomeSteps.*
import static bio.comp.jlu.asap.ASAPConstants.*


// check options
def cli = new CliBuilder( usage: "java -jar asap-${ASAP_VERSION}.jar --dir <project-directory> [-h|-i|-c|-r] [-n] [-l] [-s <#-slots>]" )
    cli.d( longOpt: 'dir',     args: 1, argName: 'project directory', required: true,  'The path to a project directory.' )
    cli.s( longOpt: 'slots',   args: 1, argName: '# grid slots',      required: false, 'Amount of grid slots ASA³P should use. Default: 50' )
    cli.h( longOpt: 'help',    args: 0, argName: 'show help',         required: false, 'Show ASAP usage.' )
    cli.i( longOpt: 'info',    args: 0, argName: 'info',              required: false, 'Show information about a certain project.' )
    cli.r( longOpt: 'reports', args: 0, argName: 'reports',           required: false, 'Only (re)create reports. Existing reports will be removed!' )
    cli.c( longOpt: 'check',   args: 0, argName: 'check config',      required: false, 'Check a spreadsheet config file along with all corresponding project files. This does not start ASA³P!' )
    cli.n( longOpt: 'clean',   args: 0, argName: 'clean project',     required: false, 'Clean project folder. Attention! This will delete all data from earlier ASA³P runs.' )
    cli.l( longOpt: 'local',   args: 0, argName: 'local execution',   required: false, 'Carry out all computations locally on the current host. Use this option if no grid engine is available.' )

def opts = cli.parse( args )
if( !opts )
    return
else if( opts.h  ||  !opts.dir )
    cli.usage()


// check ASAP_HOME env variable
final String ASAP_HOME = System.getenv()['ASAP_HOME']
if( !ASAP_HOME ) {
    println( "Error: unset environment variable ASAP_HOME!" )
    System.exit(1)
}
// check ASAP_DB env variable
final String ASAP_DB = System.getenv()['ASAP_DB']
if( !ASAP_DB ) {
    println( "Error: unset environment variable ASAP_DB!" )
    System.exit(1)
}



// check project dir and config file
Path rawProjectPath = Paths.get( opts.dir )
if( !Files.isWritable( rawProjectPath )  ||  !Files.isExecutable( rawProjectPath ) ) {
    println( "Error: project directory (${rawProjectPath}) does not exist or wrong permissions (read/write/execute) set!" )
    System.exit(1)
}
Path projectPath = rawProjectPath.toRealPath()


// clear project folder
if( opts.n ) {
    [
        PROJECT_PATH_ABR,
        PROJECT_PATH_VF,
        PROJECT_PATH_ANNOTATIONS,
        PROJECT_PATH_ASSEMBLIES,
        PROJECT_PATH_SCAFFOLDS,
        PROJECT_PATH_MAPPINGS,
        PROJECT_PATH_MLST,
        PROJECT_PATH_REFERENCES,
        PROJECT_PATH_READS_RAW,
        PROJECT_PATH_READS_QC,
        PROJECT_PATH_REPORTS,
        PROJECT_PATH_SNPS,
        PROJECT_PATH_TAXONOMY,
        PROJECT_PATH_PHYLOGENY
    ].each( {
        Path folderPath = projectPath.resolve( it )
        if( !folderPath.deleteDir()  ) {
            println( "Couldn't delete directory! dir=${folderPath}" )
            System.exit( 1 )
        }
    } )
    [
        'asap.log',
        'state.running',
        'state.failed',
        'state.finished',
        'config.json',
    ].each( {
        Path filePath = projectPath.resolve( it )
        try{
            Files.delete( filePath )
        } catch( NoSuchFileException nsfe ) {
        } catch( IOException | SecurityException ex ) {
            println( "Couldn't delete file! dir=${filePath}" )
            System.exit( 1 )
        }
    } )
}


//set project path for logging output
System.setProperty( 'PROJECT_PATH', projectPath.toString() )
log = LoggerFactory.getLogger( getClass().getName() )


log.info( "version: ${ASAP_VERSION}" )
log.info( "project-path: ${projectPath}" )
def env = System.getenv()
log.info( "USER: ${env.USER}" )
log.info( "CWD: ${env.PWD}" )
log.info( "HOSTNAME: ${env.HOSTNAME}" )
log.info( "ASAP_HOME: ${env.ASAP_HOME}" )
log.info( "ASAP_DB: ${env.ASAP_DB}" )
log.info( "PATH: ${env.PATH}" )
def props = System.getProperties()
log.info( "file.encoding: ${props['file.encoding']}" )


// convert spreadsheet config into JSON config
ConfigWriterThread.convertConfig( log, projectPath )
Path configPath = projectPath.resolve( 'config.json' )
if( !Files.exists( configPath ) ) {
    println( 'Error: config.json file does not exist!' )
    System.exit(1)
}


// parse config.json
def config = (new JsonSlurper()).parseText( configPath.toFile().text )
config.project.path = projectPath.toString() // store project path into config.json


if( opts.c ) { // only check config and data files

    checkConfig( config, projectPath )
    log.info( 'checked spreadsheet config and project data files.' )
    println( 'checked spreadsheet config and project data files.' )
    System.exit( 0 )

} else if( opts.i ) { // only show info and statistics about current pipeline


    // print config.json
    printInfo( config )

    // print runtime statistics
    if( Files.exists( projectPath.resolve( 'state.finished' ) ) )
        printRuntime( config )


} else if( opts.r ) { // (re)create reports

    if( !Files.exists( projectPath.resolve( 'state.finished' ) ) ) {
        println( 'Error: couldn\'t detect a former pipeline run! (no state.finished)' )
        System.exit( 1 )
    }

    def configWriterThread = new ConfigWriterThread( config )
        configWriterThread.start() // start config writer thread

    // start html reporting
    def reportRunner = new ReportRunner( config )
        reportRunner.start()
        reportRunner.waitFor()


    // shutdown config writer thread
    configWriterThread.finish()
    configWriterThread.join()


} else { // normal setup, start pipeline


    /* perform initialization code
     * before pipeline steps start
     */

    int slotSize // set / estimate suitable slot size
    boolean localMode = false
    if( opts.l ) {
        // use twice the estimated max per step CPU usage (8) due to parallel task execution
        slotSize = Runtime.getRuntime().availableProcessors() / (2*8)
        localMode = true
        if( slotSize < 1 )
            slotSize = 1
        log.info( 'execution mode: local' )
        log.info( "slot size: ${slotSize}" )
    } else if( opts.s ) {
        log.info( 'execution mode: grid' )
        try {
            slotSize = Integer.parseInt( opts.s )
            log.info( "custom slot size: ${slotSize}" )
        }
        catch( Exception ex ) {
            println( 'Error: wrong slot parameter!' )
            System.exit( 1 )
        }
    } else {
        slotSize = 50
        log.info( 'default slot size: 50' )
    }


    log.trace( 'write "state.running" file' )
    if( Files.exists( projectPath.resolve( 'state.finished' ) ) )
        Files.move( projectPath.resolve( 'state.finished' ), projectPath.resolve( 'state.running' ) )
    else if( Files.exists( projectPath.resolve( 'state.running' ) ) ) {
        println( 'Error: state.running file indicates an already running instance!' )
        System.exit( 1 )
    } else
        Files.createFile( projectPath.resolve( 'state.running' ) )


    Date startTime = new Date()
    config.dates.start = startTime.format( DATE_FORMAT )

    checkConfig( config, projectPath )
    setupStepSelections( config ) // sanitize step selection

    if( !Files.exists( projectPath.resolve( 'state.finished' ) ) ) // make sure it's the first run
        setupDirectories( config, projectPath ) // create project folder subdirectories

    def configWriterThread = new ConfigWriterThread( config )
        configWriterThread.start() // start config writer thread

    printInfo( config ) // print config.json

    if( !config.analyses ) // tmp fix for config bug
        config.analyses = [:]
    if( !config.steps ) // tmp fix for config bug
        config.steps = [:]


    // start init steps
    def initSteps = [
        new ReferenceProcessings( config ),
        new MappingIndices( config ),
        new SNPAnnotationSetup( config )
    ]
    initSteps.each( { it.start() } )


    // wait for init steps
    initSteps.each( {
        it.waitFor()
        if( it.getStatus() == FAILED ) {
            println( "Error: init step (${it.getStepName()}) failed!" )
            System.exit( 1 )
        }
    } )


    /* start genome runners
     * in order to run genome specific Steps
     */
    def threadPool = Executors.newFixedThreadPool( slotSize, new ASAPThreadFactory() )
    try {
        List<Future> futures = config.genomes.collect{ genome ->
            threadPool.submit( new GenomeRunner( config, genome, localMode ) );
        }
        futures.each( { it.get() } )
    } catch( Throwable ex ) {
        log.error( "error in thread pool!", ex )
        println( 'Error: something horrable happened internally!' )
        System.exit( 1 )
    }


    // start global analyses steps after single runs ran in parallel
    Step analysesRunner = new AnalysesRunner( config, localMode ) // start analyses runner
        analysesRunner.start()
        analysesRunner.waitFor()


    Date endTime = new Date()
    config.dates.end = endTime.format( DATE_FORMAT )


    // start html reporting
    def reportRunner = new ReportRunner( config )
        reportRunner.start()
        reportRunner.waitFor()


    // shutdown config writer thread
    configWriterThread.finish()
    configWriterThread.join()


    // print runtime statistics
    printRuntime( config )


    log.trace( 'move "state.running" into "state.finished"' )
    Files.move( projectPath.resolve( 'state.running' ), projectPath.resolve( 'state.finished' ) )

}




def checkConfig( def config, Path projectPath ) {

    log.trace( 'check config file...' )

    log.trace( 'check user info' )
    if( !config.user  ||  !config.user.name  ||  !config.user.surname  || !config.user.email ) {
        println( 'Error: config contains no / wrong user information!' )
        System.exit( 1 )
    }

    if( !(config.user.email ==~ /[\w-_\.]+@(?:[\w+-]+\.)+[a-z]{2,4}/) ) {
        println( 'Error: wrong user email!' )
        System.exit( 1 )
    }

    config.user.name = config.user.name.trim()
    config.user.surname = config.user.surname.trim()
    config.user.email = config.user.email.trim()

    log.trace( 'check project info' )
    if( !config.project  ||  !config.project.name  ||  !config.project.description  ||  !config.project.genus  ||  !config.project.path ) {
        println( 'Error: config contains no / wrong project information!' )
        System.exit( 1 )
    }

    if( !(config.project.genus.trim() ==~ /[a-zA-Z]{5,20}/) ) {
        println( 'Error: wrong genus!' )
        System.exit( 1 )
    }

    config.project.name = config.project.name.trim()
    config.project.description = config.project.description.trim()
    config.project.genus = config.project.genus.trim().toLowerCase().capitalize()
    config.project.path = config.project.path.trim()

    Path dataPath = projectPath.resolve( PROJECT_PATH_DATA )
    log.trace( 'check genomes' )
    if( !config.genomes ) {
        println( 'Error: config contains no / wrong project information!' )
        System.exit( 1 )
    } else {
        def idList = []
        def sampleList = []
        config.genomes.each( { genome ->
            if( !genome.id  ||  !genome.species  ||  !genome.strain ) {
                println( "Error: config contains no / wrong genome information!\n(${genome})" )
                System.exit( 1 )
            }
            String species = genome.species.trim().toLowerCase()
            if( species != 'sp.'  &&  !(species ==~ /[a-z]{2,50}/) ) {
                println( "Error: wrong species (${species})!" )
                System.exit( 1 )
            }
            genome.species = species
            String strain = genome.strain.trim()
            if( !(strain ==~ /([\w-\.]){2,50}/) ) {
                println( "Error: wrong characters in strain (${strain})!" )
                System.exit( 1 )
            }
            genome.strain = strain

            String genomeName = "${config.project.genus}_${genome.species}_${genome.strain}"
            if( sampleList.contains( genomeName ) ) {
                println( "Error: duplicated genome name in config! (${genomeName})" )
                System.exit( 1 )
            }
            sampleList << genomeName

            if( idList.contains( genome.id ) ) {
                println( "Error: duplicated genome id in config! (${genome.id})" )
                System.exit( 1 )
            }
            idList << genome.id

            genome.data.each( { read ->
                if( !read.type ) {
                    println( "Error: read file without type information!\n(${genome})" )
                    System.exit( 1 )
                }
                FileType ft = FileType.getEnum( read.type )
                try {
                    if( ft?.getDataType() == DataType.READS ) {
                        Path destPath = projectPath.resolve( PROJECT_PATH_READS_RAW )
                        read.files.each( {
                            if( !Files.isReadable( dataPath.resolve( it ) )  &&  !Files.isReadable( destPath.resolve( it ) ) ) {
                                throw new IOException( "could not read file ${it}" )
                            }
                        } )
                    } else {
                        Path destPath
                        if( ft == FileType.CONTIGS ) {
                            destPath = Paths.get( projectPath.toString(), PROJECT_PATH_ASSEMBLIES, genomeName, "${genomeName}.fasta" )
                        } else if( ft == FileType.CONTIGS_ORDERED ) {
                            destPath = Paths.get( projectPath.toString(), PROJECT_PATH_SCAFFOLDS, genomeName, "${genomeName}.fasta" )
                        } else if( ft == FileType.GENOME ) {
                            destPath = Paths.get( projectPath.toString(), PROJECT_PATH_ANNOTATIONS, genomeName, "${genomeName}.gbk" )
                        } else {
                            log.error( "Error: wrong file type detected! (${ft})" )
                            println( "Error: wrong file type detected! (${ft})" )
                            System.exit( 1 )
                        }
                        String fileName = read.files[0]
                        if( !Files.isReadable( dataPath.resolve( fileName ) )  &&  !Files.isReadable( destPath ) ) {
                            throw new IOException( "could not read file ${fileName}" )
                        }
                    }
                } catch( IOException ex ) {
                    log.error( "Error: file does not exist or is not readable! (${read.files})", ex )
                    println( "Error: read file does not exist or is not readable! (${read.files})" )
                    System.exit( 1 )
                }
            } )
        } )
    }


    log.trace( 'check references' )
    if( config.references ) {
        Path referencePath = projectPath.resolve( PROJECT_PATH_REFERENCES )
        config.references.each( { ref ->
            if( !(ref ==~ /[a-zA-Z0-9\._-]{2,}/) ) {
                println( "Error: wrong reference file name (${ref})!" )
                System.exit( 1 )
            } else if( !Files.isReadable( dataPath.resolve( ref ) )  &&  !Files.isReadable( referencePath.resolve( ref ) ) ) {
                println( "Error: reference file does not exist or is not readable! (${ref})" )
                System.exit( 1 )
            } else if( !ref.contains( '.' )  ||  ReferenceType.getEnum( ref.substring( ref.lastIndexOf( '.' ) + 1 ) ) == null ) {
                println( "Error: reference file has wrong file suffix! (${ref})" )
                System.exit( 1 )
            }
        } )
    } else {
        println( "Error: empty reference list! Please, specify at least 1 reference genome." )
        System.exit( 1 )
    }

}


def setupStepSelections( config ) {

    // parse provided input data and add necessary steps
    config.genomes.each( { genome ->

        Set<GenomeSteps> necessarySteps = new LinkedHashSet<>()

        FileType ft = FileType.getEnum( genome.data[0].type )
        if( ft?.getDataType() == DataType.READS ) {
            necessarySteps << QC
            necessarySteps << ASSEMBLY
            necessarySteps << SCAFFOLDING
            necessarySteps << ANNOTATION
            necessarySteps << TAXONOMY
            necessarySteps << MLST
            necessarySteps << ABR
            necessarySteps << VF
            necessarySteps << MAPPING
            necessarySteps << SNP_DETECTION
        } else if( ft == FileType.CONTIGS ) {
            necessarySteps << SCAFFOLDING
            necessarySteps << ANNOTATION
            necessarySteps << TAXONOMY
            necessarySteps << MLST
            necessarySteps << ABR
            necessarySteps << VF
        } else if( ft == FileType.CONTIGS_ORDERED  ||  ft == FileType.CONTIGS_LINKED ) {
            necessarySteps << ANNOTATION
            necessarySteps << TAXONOMY
            necessarySteps << MLST
            necessarySteps << ABR
            necessarySteps << VF
        }

        genome.stepselection = necessarySteps.collect( { it.getCharCode() } ).join( '' )

    } )

}


def setupDirectories( def config, def projectPath ) {

    log.debug( 'build project folder structure' )
    [
        PROJECT_PATH_REFERENCES,
        PROJECT_PATH_READS_RAW,
        PROJECT_PATH_READS_QC,
        PROJECT_PATH_ASSEMBLIES,
        PROJECT_PATH_SCAFFOLDS,
        PROJECT_PATH_ANNOTATIONS,
        PROJECT_PATH_TAXONOMY,
        PROJECT_PATH_MLST,
        PROJECT_PATH_ABR,
        PROJECT_PATH_VF,
        PROJECT_PATH_MAPPINGS,
        PROJECT_PATH_SNPS
    ].each( { folderName ->
        Path folderPath = projectPath.resolve( folderName )
        if( !Files.exists( folderPath ) ) {
            try{
                Files.createDirectory( folderPath )
                log.debug( "created folder: ${folderPath}" )
            } catch( FileAlreadyExistsException faee ) {
                log.debug( "folder already existed: ${folderPath}" )
            }
        }
    } )


    // copy (create hard links) files from DATA folder into corresponding subdirs
    try {
        Path dataPath = projectPath.resolve( PROJECT_PATH_DATA )
        config.genomes.each( { genome ->
            String genomeName = "${config.project.genus}_${genome.species}_${genome.strain}"
            genome.data.each( { read ->
                FileType ft = FileType.getEnum( read.type )
                if( ft?.getDataType() == DataType.READS ) {
                    Path destPath = Paths.get( projectPath.toString(), PROJECT_PATH_READS_RAW, genomeName )
                    if( !Files.exists( destPath ) ) Files.createDirectory( destPath )
                    read.files.each( { Files.createLink( destPath.resolve( it ), dataPath.resolve( it ) ) } )
                } else if( ft == FileType.CONTIGS ) {
                    Path destPath = Paths.get( projectPath.toString(), PROJECT_PATH_ASSEMBLIES, genomeName )
                    if( !Files.exists( destPath ) ) Files.createDirectory( destPath )
                    Files.createLink( destPath.resolve( "${genomeName}.fasta" ), dataPath.resolve( read.files[0] ) )
                } else if( ft == FileType.CONTIGS_ORDERED ) {
                    Path destPath = Paths.get( projectPath.toString(), PROJECT_PATH_SCAFFOLDS, genomeName )
                    if( !Files.exists( destPath ) ) Files.createDirectory( destPath )
                    Files.createLink( destPath.resolve( "${genomeName}.fasta" ), dataPath.resolve( read.files[0] ) )
                } else if( ft == FileType.GENOME ) {
                    Path destPath = Paths.get( projectPath.toString(), PROJECT_PATH_ANNOTATIONS, genomeName )
                    if( !Files.exists( destPath ) ) Files.createDirectory( destPath )
                    Files.createLink( destPath.resolve( "${genomeName}.gbk" ), dataPath.resolve( read.files[0] ) )
                }
            } )
        } )
        config.references.each( { ref ->
            Files.createLink( Paths.get( projectPath.toString(), PROJECT_PATH_REFERENCES, ref ), dataPath.resolve( ref ) )
        } )
    } catch( Throwable t ) {
        log.error( "Error: could not copy data content to corresponding subfolders!", t )
        println( "Error: could not copy data content to corresponding subfolders!" )
        System.exit( 1 )
    }

}


def printInfo( config ) {

    println( "ASAP version ${ASAP_VERSION}" )
    println()

    println( 'project:' )
    println( '\tname:'.padRight(12) + config.project.name )
    println( '\tdesc:'.padRight(12) + config.project.description )
    println( '\tpath:'.padRight(12)  + config.project.path )
    println( '\tgenus:'.padRight(12)  + config.project.genus )
    println( '\treferences:' )
    config.references.each( { println( "\t\t${it}" ) } )

    println()
    println( 'user:' )
    println( '\tname:'.padRight(12)    + config.user.name )
    println( '\tsurname:'.padRight(12) + config.user.surname )
    println( '\temail:'.padRight(12)   + config.user.email )

    println()
    println( 'genomes:' )
    config.genomes.each( {

        println( '\tid:'.padRight(12)       + "${it.id}" )
        println( '\torganism:'.padRight(12) + "${config.project.genus.substring(0,1)}. ${it.species} ${it.strain}" )
        println( '\tdata: ' )

        int i = 1;
        it.data.each( { datum ->
            println( "\t\t[${i++}]: ${datum.type}, ${datum.files}" )
        } )
        println()

    } )

}


def printRuntime( config ) {

    def stepsStatistics = [:]
    GenomeSteps.values().each( { stepsStatistics[ it.getAbbreviation() ] = [ 'amount': 0, 'sum': 0L, 'min': Integer.MAX_VALUE, 'max': 0L, 'suc': 0, 'err': 0, 'skip': 0 ] } )

    // print genome statistics
    println()
    println( 'genome statistics:' )
    config.genomes.each( {
        if( it.steps )
            println( "\tid: ${it.id}, organism: ${config.project.genus} ${it.species} ${it.strain}" )
        it.steps.each( { abbr, step ->
            if( step.status == FINISHED.toString() ) {
                stepsStatistics[ abbr ].suc++
                if( step.start  &&  step.end  ) {
                    int runtime = (int)( Date.parse( DATE_FORMAT, step.end ).getTime() - Date.parse( DATE_FORMAT, step.start ).getTime() )
                    if( runtime < stepsStatistics[ abbr ].min )
                    stepsStatistics[ abbr ].min = runtime
                    if( runtime > stepsStatistics[ abbr ].max )
                    stepsStatistics[ abbr ].max = runtime
                    stepsStatistics[ abbr ].amount++
                    stepsStatistics[ abbr ].sum += runtime
                    println( String.format( '\t%-11s %s, %s, (%s -> %s)', abbr+':', step.status, Misc.formatRuntimes( runtime ), step.start, step.end ) )
                } else {
                    println( String.format( '\t%-11s %s', abbr+':', step.status ) )
                }
            } else if( step.status == SKIPPED.toString() )
                stepsStatistics[ abbr ].skip++
            else if( step.status == FAILED.toString() )
                stepsStatistics[ abbr ].err++
            else if( step.status == null )
                step.status == ''
        } )
        if( it.steps )
            println()
    } )


    // print step statistics
    println()
    println( String.format( '%-28s %4s | %4s | %4s \t %12s | %12s | %12s', 'genome analyses statistics:', 'suc', 'err', 'skip', 'min', 'mean', 'max' ) );
    stepsStatistics.each( { abbr, step ->
        String minRuntime  = step.amount > 0 ? Misc.formatRuntimes( step.min ) : '-'
        String meanRuntime = step.amount > 0 ? Misc.formatRuntimes( (int)(step.sum / step.amount) ) : '-'
        String maxRuntime  = step.amount > 0 ? Misc.formatRuntimes( step.max ) : '-'
        println( String.format( '\t%-20s %4d | %4d | %4d \t %12s | %12s | %12s', "${abbr} steps:", step.suc, step.err, step.skip, minRuntime, meanRuntime, maxRuntime ) )
    } )


    // print analyses statistics
    println()
    println( 'comparative analyses statistics:' )
    if( config.analyses ) {
        AnalysesSteps.values().each{ it ->
            def analysis = config.analyses[ it.getAbbreviation() ]
            if( analysis != null  &&  analysis?.start != null  &&  analysis?.end != null ) {
                int runtime = (int)( Date.parse( DATE_FORMAT, analysis.end ).getTime() - Date.parse( DATE_FORMAT, analysis.start ).getTime() )
                println( String.format( '\t%-16s %s, %s, (%s -> %s)', it.getName()+':', analysis.status, Misc.formatRuntimes( runtime ), analysis.start, analysis.end ) )
            } else
                println( String.format( '\t%-16s %s', it.getName()+':', analysis?.status ?: '-' ) )
        }
    } else
        println( '-> no executed analyses detected!' )


    // print total statistics
    println()
    println()
    if( config.dates.start  &&  config.dates.end ) {
        totalRuntimes = Misc.formatRuntimes( (int)(Date.parse( DATE_FORMAT, config.dates.end ).getTime() - Date.parse( DATE_FORMAT, config.dates.start ).getTime()) )
        println( "total runtime: ${totalRuntimes}" )
    } else
        println( 'total runtime:' )
    println( '\tstart: '  + (config.dates.start ?: '-') )
    println( '\tend:   '  + (config.dates.end ?: '-') )

}


def exit( String msg, Exception ex ) {

    if( ex )
        log.error( msg, ex )
    else
        log.error( msg )
    println( "Error: ${msg}" )
    System.exit( 1 )

}