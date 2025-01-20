
# Use an official OpenJDK runtime as a parent image
FROM openjdk:11-jre-slim

# Set environment variables
ENV APP_HOME /opt/dhis-integration
ENV CONFIG_DIR /etc/dhis-integration
ENV LOG_DIR /var/log/dhis-integration
ENV DATA_DIR ${APP_HOME}/dhis-integration-data

# Create necessary directories
RUN mkdir -p ${APP_HOME}/bin ${CONFIG_DIR} ${LOG_DIR} ${DATA_DIR}

# Set the working directory
WORKDIR ${APP_HOME}

# Copy the JAR file to the application directory
COPY target/dhis-integration-1.0-SNAPSHOT.jar ${APP_HOME}/bin/dhis-integration.jar

# Copy the configuration file
COPY src/main/resources/application.yml ${CONFIG_DIR}/dhis-integration.yml
COPY src/main/resources/log4j.properties ${CONFIG_DIR}/log4j.properties

# Copy the start.sh script and make it executable
COPY src/scripts/rpm/start.sh ${APP_HOME}/bin/start.sh
RUN chmod +x ${APP_HOME}/bin/start.sh

# Set permissions (if necessary)
RUN chmod -R 777 ${LOG_DIR} ${DATA_DIR}

# Set the entrypoint to use the start.sh script
ENTRYPOINT ["java", "-jar", "/opt/dhis-integration/bin/dhis-integration.jar", \
 "--spring.config.location=/etc/dhis-integration/dhis-integration.yml"]