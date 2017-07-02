package com.github.logminer.sourceanalyzer;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseProblemException;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.SetMultimap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.MessageFormat;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Created by siva on 7/1/17.
 * Poor man's implementation to analyze log statements in Java Project
 * Currently parses only direct logger statements and make lots of assumptions
 * Will be enhanced in future to handle multiple log libraries ( currently supports log4j) and
 * handle multiple logging statement contexts. May be a maven plugin
 */
public class JavaSrcAnalyzer {

    private String srcRoot, outputFile;
    private static final Set<String> LOG_METHODS = new HashSet<String>();
    private int fileCount = 0, logCount = 0;
    private BufferedWriter writer;
    private static final String REGEX_SPECIAL_CHARS = "[\\<\\(\\[\\\\\\^\\-\\=\\$\\!\\|\\]\\)‌​\\?\\*\\+\\.\\>]";
    private static Logger LOGGER = LoggerFactory.getLogger(JavaSrcAnalyzer.class);
    private static final Pattern LOG_FORMAT_ELEMENT_PATTERN = Pattern.compile("\\{}");
    private static final Pattern REGEX_SPECIAL_CHARS_PATTERN = Pattern.compile(REGEX_SPECIAL_CHARS);

    static {
        LOG_METHODS.add("debug");
        LOG_METHODS.add("trace");
        LOG_METHODS.add("info");
        LOG_METHODS.add("warn");
        LOG_METHODS.add("error");
    }

    public JavaSrcAnalyzer(String srcRoot, String outputFile) {
        this.srcRoot = srcRoot;
        this.outputFile = outputFile;
    }

    public void analyze() throws IOException {

        Path path = Paths.get(srcRoot);
        if (Files.isDirectory(path)) {
            try {
                writer = new BufferedWriter(new FileWriter(outputFile));
                Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        if (file.toString().endsWith(".java")) {
                            try {
                                analyzeFile(file.toFile());
                                fileCount++;
                            } catch (ParseProblemException e) {
                                LOGGER.warn("Exception while analyzing file {}", file,e);
                            }
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
                writer.flush();
                System.out.println(MessageFormat.format("Analyzed {0} logs in {1} files", logCount, fileCount));
            } finally {
                if (writer != null) {
                    writer.close();
                }
            }
        } else {
            System.err.println("Specify a src directory");
        }
    }

    private void analyzeFile(File file) throws IOException {
        CompilationUnit cu = JavaParser.parse(file);
        List<MethodCallExpr> methodCallExprList = cu.getChildNodesByType(MethodCallExpr.class);

        //Get all field declarations in this file
        HashMultimap<String, String> classToFieldsMap = HashMultimap.create();
        List<ClassOrInterfaceDeclaration> classes = cu.getChildNodesByType(ClassOrInterfaceDeclaration.class);
        for (ClassOrInterfaceDeclaration clazz : classes) {
            List<FieldDeclaration> declarations = clazz.getFields();
            for (FieldDeclaration declaration : declarations) {
                for (VariableDeclarator d : declaration.getVariables()) {
                    classToFieldsMap.put(clazz.getNameAsString(), d.getNameAsString());
                }
            }
        }

        //loop through all method calls in this file
        for (MethodCallExpr methodCallExpr : methodCallExprList) {
            String methodName = methodCallExpr.getName().getIdentifier();
            if (LOG_METHODS.contains(methodName)) {
//                SymbolReference<MethodDeclaration> solved = parserFacade.solve(methodCallExpr);
//                if(solved.isSolved()) {
//                    System.out.println(solved.getCorrespondingDeclaration().getPackageName());
//                }
                int argCount = methodCallExpr.getArguments().size();
                if (argCount > 0) {
                    Expression firstArg = methodCallExpr.getArguments().get(0);
                    if (firstArg instanceof StringLiteralExpr) {
                        String logString = ((StringLiteralExpr) firstArg).asString();
                        JavaSrcAnalyzer.LogStatement logStatement = new JavaSrcAnalyzer.LogStatement();
                        try {
                            logStatement.messageRegEx = convertToRegEx(logString);
                            logStatement.level = methodName;
                            logStatement.clazz = getLogDeclarationClass(methodCallExpr, classToFieldsMap,file);
                            writer.append(logStatement.toString()).append('\n');
                        } catch (PatternSyntaxException ex) {
                            LOGGER.warn("Exception while converting regex {} in file {}. Message {}" , logString, file,ex.getMessage());
                        }
                        logCount++;
                    }
                } else {
                    LOGGER.warn("Cannot resolve logger statement {} in file {}" , methodCallExpr, file);
                }
            }
        }
    }

    //Creates regEx pattern from message with named groups
    private Pattern convertToRegEx(String message) {
        String cleanedUpMessage = REGEX_SPECIAL_CHARS_PATTERN.matcher(message).replaceAll("\\\\$0");
        cleanedUpMessage = LOG_FORMAT_ELEMENT_PATTERN.matcher(cleanedUpMessage).replaceAll("([\\\\\\\\w]+)");
        return Pattern.compile(cleanedUpMessage);
    }


    private String getLogDeclarationClass(MethodCallExpr methodCallExpr, SetMultimap<String, String> classToFieldsMap,File file) {
        Optional<Expression> scope = methodCallExpr.getScope();
        String logClass = "Default-Class";
        NameExpr nameExpr = null;
        if (scope.isPresent() && scope.get() instanceof NameExpr) {
            nameExpr = (NameExpr) scope.get();
        } else {
            LOGGER.warn("Cannot resolve parent class for {} in file {}" , methodCallExpr, file);
            return logClass;
        }

        String varName = nameExpr.getNameAsString();
        Optional<ClassOrInterfaceDeclaration> clazz =
                methodCallExpr.getAncestorOfType(ClassOrInterfaceDeclaration.class);

        while (true) {
            Set<String> fields = classToFieldsMap.get(clazz.get().getNameAsString());
            if (fields.contains(varName)) {
                logClass = clazz.get().getNameAsString();
                break;
            } else {
                clazz = clazz.get().getAncestorOfType(ClassOrInterfaceDeclaration.class);
                if (!clazz.isPresent()) {
                    break;
                }
            }
        }
        return logClass;
    }

    static class LogStatement {
        private Pattern messageRegEx;
        private String level;
        private String clazz;

        public String toString() {
            return this.level + "|" + this.clazz + "|" + this.messageRegEx.pattern();
        }
    }

    public static void main(String args[]) throws Exception {
        if (args.length == 2) {
            JavaSrcAnalyzer analyzer = new JavaSrcAnalyzer(args[0], args[1]);
            analyzer.analyze();
        } else {
            System.out.println("Usage: JavaSrcAnalyzer <srcDir> <outputFile>");
        }
    }
}
