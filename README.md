# Logtrail-tools
Consists a bunch of tools that can be used to enhance the log messages shown in logtrail. Currently there are 2 modules

### Source Analyzer 

Analyze the source code to extract logger information along with the variables logged. This will convert all the 
log statement in source code into patterns that can be used by log-parser. Currently supports analyzing Java source code
to find patterns for slf4j log methods. Will be extended in future to support multiple loggers in Java and for other 
languages like go, etc. 

This tool will be run as part of compilation of the application source code and will generate pattern file as output.

### Log Parser

Consists of logstash filter plugin and a Java API used by the plugin. Given the pattern file as input, this will match 
the log messages to the pattern and extract the variables from the message. The extracted variables will be added as 
additional info to the JSON source document to be stored in Elasticsearch.
