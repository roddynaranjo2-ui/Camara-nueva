#!/usr/bin/env sh

##############################################################################
##
##  Gradle start up script for UN*X
##
##############################################################################

# Attempt to set APP_HOME
# Resolve links: $0 may be a link
PRG="$0"
# Need this for relative symlinks.
while [ -h "$PRG" ] ; do
    ls=`ls -ld "$PRG"`
    link=`expr "$ls" : '.*-> \(.*\)$'`
    if expr "$link" : '/.*' > /dev/null; then
        PRG="$link"
    else
        PRG=`dirname "$PRG"`"/$link"
    fi
done
SAVED="`pwd`"
cd "`dirname "$PRG"`" >/dev/null
APP_HOME="`pwd -P`"
cd "$SAVED" >/dev/null

APP_NAME="Gradle"
APP_BASE_NAME=`basename "$0"`

# Add default JVM options here. You can also use JAVA_OPTS and GRADLE_OPTS to pass JVM options to the daemon.
DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'

# Use the maximum available, or set MAX_FD != -1 to turn off the limit.
MAX_FD="maximum"

warn () {
    echo "$@"
}

# OS specific support.  $var must _not_ be set to non-empty string
use_tricks () {
    case `uname` in
        CYGWIN*)
            use_tricks=true
            ;;
        Darwin*)
            use_tricks=true
            ;;
        MINGW*)
            use_tricks=true
            ;;
        *)
            use_tricks=false
            ;;
    esac
}

# Determine the Java command to use to start the wrapped application.
if [ -n "$JAVA_HOME" ] ; then
    if [ -x "$JAVA_HOME/jre/sh/java" ] ; then
        # IBM's JDK on AIX uses strange locations for the executables
        JAVACMD="$JAVA_HOME/jre/sh/java"
    else
        JAVACMD="$JAVA_HOME/bin/java"
    fi
    if [ ! -x "$JAVACMD" ] ; then
        die "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME

Please set the JAVA_HOME variable in your environment to match the
location of your Java installation."
    fi
else
    JAVACMD="java"
    which java >/dev/null 2>&1 || die "ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.

Please set the JAVA_HOME variable in your environment to match the
location of your Java installation."
fi

# Increase the maximum file descriptors if low.
if [ "$use_tricks" = "true" ] ; then
    MAX_FD_LIMIT=`ulimit -H -n`
    if [ "$MAX_FD_LIMIT" != "unlimited" ] ; then
        ulimit -n 4096 || warn "Could not set maximum file descriptors to 4096."
    fi
fi

# For Darwin, add options to specify how the application
# grabs memory (more than 2GB)
if $use_tricks ; then
    GRADLE_OPTS="$GRADLE_OPTS -Dorg.gradle.appname=$APP_BASE_NAME"
fi

# Collect all arguments for the java command, following the shell quoting and substitution rules
# that are documented in the man page for the 'sh' shell.
eval set -- "${DEFAULT_JVM_OPTS} ${JAVA_OPTS} ${GRADLE_OPTS} \"-Dorg.gradle.appname=$APP_BASE_NAME\" @args"

exec "$JAVACMD" "$@"
