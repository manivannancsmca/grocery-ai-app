package com.grocery.ci

/** Shell quoting for the commands the library builds (printed in DRY_RUN exactly as they would run). */
class Shell implements Serializable {

    private static final long serialVersionUID = 1L

    /** Single-quotes an argument unless it only contains characters that are safe unquoted. */
    @NonCPS
    static String quote(Object value) {
        String s = String.valueOf(value)
        if (s ==~ /[A-Za-z0-9_.\/:=@%+,-]+/) {
            return s
        }
        return "'" + s.replace("'", "'\\''") + "'"
    }

    /** ['aws', 'ecs', 'wait', ...] -> the command line. */
    @NonCPS
    static String join(List args) {
        return args.collect { quote(it) }.join(' ')
    }

    /** A value safe in a Docker container name / tag / file name. */
    @NonCPS
    static String slug(Object value) {
        return String.valueOf(value).toLowerCase().replaceAll(/[^a-z0-9_.-]+/, '-').replaceAll(/^-+|-+$/, '')
    }
}
