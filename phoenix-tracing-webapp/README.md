# TracingWebApp
1. Build the web application-
 *mvn clean install*

2. Start the TracingWebApp
 *java -jar target/phoenix-tracing-webapp-4.5.0-SNAPSHOT-runnable.jar*

3. View the Content -
 *http://localhost:8890/webapp/#*

 ###Note
 You can set the port of the trace app by 
 -Dphoenix.traceserver.http.port={portNo}

 eg:
 `-Dphoenix.traceserver.http.port=8890` server will start in 8890

 This is a milestone 5
