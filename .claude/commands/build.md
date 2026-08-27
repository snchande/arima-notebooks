# Build Arima Notebooks

Builds the Arima Notebooks project using Maven.

## Full build with tests
```bash
cd $BARISTA_HOME && mvn clean package
```

## Fast build (skip tests)
```bash
cd $BARISTA_HOME && mvn clean package -DskipTests
```

## Build output
The JAR will be at: `target/arima-notebooks-4.0.1.jar`

## Run the built JAR
```bash
java --add-opens=jdk.jshell/jdk.jshell=ALL-UNNAMED \
     --add-opens=java.base/java.lang=ALL-UNNAMED \
     --add-exports=jdk.jshell/jdk.jshell=ALL-UNNAMED \
     -jar target/arima-notebooks-4.0.1.jar
```
