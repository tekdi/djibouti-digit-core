FROM egovio/amazoncorretto:17-alpine3.19 AS build
ARG WORK_DIR=.
WORKDIR /app

RUN apk add --no-cache maven

COPY ${WORK_DIR}/pom.xml ./pom.xml
COPY start.sh ./start.sh

COPY ${WORK_DIR}/src ./src
RUN mvn -B -f /app/pom.xml package -DskipTests


FROM egovio/amazoncorretto:17-alpine3.19

WORKDIR /opt/egov

COPY --from=build /app/target/*.jar /app/start.sh /opt/egov/

RUN chmod +x /opt/egov/start.sh

CMD ["/opt/egov/start.sh"]