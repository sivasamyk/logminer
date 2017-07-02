import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by siva on 7/1/17.
 * Parse log4j logs with default pattern
 *
 */
public class LogParser {
    private File patternsFile;
    private Multimap<String, LogStatement> logStatements = ArrayListMultimap.create();
    private static final Logger LOGGER = LoggerFactory.getLogger(LogParser.class);

    public LogParser(String patternsFilePath) throws IOException {
        this.patternsFile = new File(patternsFilePath);
        populatePatterns();
    }

    public List<ParsedLog> parseLogsInDir(String logsDir) throws IOException {
        Path dir = Paths.get(logsDir);
        List<ParsedLog> parsedLogs = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path logFile: stream) {
                BufferedReader reader = Files.newBufferedReader(logFile, StandardCharsets.UTF_8);
                reader.lines().forEach(s -> {
                    ParsedLog parsedLog = parseLogLine(s);
                    if (parsedLog != null) {
                        parsedLogs.add(parsedLog);
                    }
                });
            }
        }
        return parsedLogs;
    }

    /**
     * log pattern is 2016-12-21 17:10:56,844 | INFO  | -message-handler | ApplicationManager|
     * 76 - org.onosproject.onos-core-net - 1.8.1.SNAPSHOT | Application org.onosproject.ovsdbhostprovider
     * has been installed
     * @param line - log line
     */
    private ParsedLog parseLogLine(String line) {
        ParsedLog parsedLog = null;
        String tokens[] = line.split("\\|");
        if (tokens.length == 6) {
            String message = tokens[5].trim();
            String level = tokens[1].trim().toLowerCase();
            String clazz = tokens[3].trim();
            parsedLog = parse(message, clazz);
        }
        return parsedLog;
    }

    private ParsedLog parse(String message, String clazz) {
        ParsedLog parsedLog = null;
        Collection<LogStatement> logs = logStatements.get(clazz);
        if (logs.isEmpty()) {
            logs = logStatements.get("Default-Class");
        }
        if (logs != null && logs.size() > 0) {
            for (LogStatement logStmt : logs) {
                Matcher matcher = logStmt.messageRegEx.matcher(message);
                StringBuilder matchInfo = new StringBuilder();
                parsedLog = new ParsedLog();
                parsedLog.log = message;
                if (matcher.matches()) {
                    matchInfo.append(message + " | " +
                            logStmt.messageRegEx.toString());
                    parsedLog.regEx = logStmt.messageRegEx.toString();
                    if(matcher.groupCount() > 0) {
                        String groups[] = new String[matcher.groupCount()];
                        for (int i=1;i<=matcher.groupCount();i++) {
                            groups[i-1] = (matcher.group(i));
                        }
                        parsedLog.matches = groups;
                    }
                    matchInfo.append(" | in class " + logStmt.clazz);
                    parsedLog.clazz = logStmt.clazz;
                    LOGGER.debug("Parsed log line is {}", parsedLog);
                    break;
                }
            }
        }
        return parsedLog;
    }

    private void populatePatterns() throws IOException {
        BufferedReader reader = Files.newBufferedReader(patternsFile.toPath(), StandardCharsets.UTF_8);
        reader.lines().forEach(line -> {
            String tokens[] = line.split("\\|");
            if (tokens.length == 3) {
                LogStatement logStatement = new LogStatement();
                logStatement.level = tokens[0];
                logStatement.clazz = tokens[1];
                logStatement.messageRegEx = Pattern.compile(tokens[2]);
                logStatements.put(logStatement.clazz, logStatement);
            }
        });
    }

    static class LogStatement {
        private Pattern messageRegEx;
        private String level;
        private String clazz;
    }

    static class ParsedLog {
        String log;
        String clazz;
        String regEx;
        String[] matches;

        @Override
        public String toString() {
            return "ParsedLog{" +
                    "log='" + log + '\'' +
                    ", clazz='" + clazz + '\'' +
                    ", regEx='" + regEx + '\'' +
                    ", matches=" + Arrays.toString(matches) +
                    '}';
        }
    }

    public static void main(String args[]) throws IOException {
        if (args.length == 2 ) {
            LogParser logParser = new LogParser(args[0]);
            List<ParsedLog> parsedLogs = logParser.parseLogsInDir(args[1]);
            for(ParsedLog parsedLog : parsedLogs) {
                System.out.println(parsedLog);
            }
        } else {
            System.out.println("Usage : LogParser <patternsFile> <logsDir>");
        }
    }
}
