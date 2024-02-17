# JMS Camel
Experiment to work with [Camel](https://camel.apache.org/), 
[Spring Boot](https://spring.io/projects/spring-boot) and [JMS](https://activemq.apache.org/components/artemis/) and filter all repeating JMS messages.

## Working

The following steps are performed:

1. create standard JMS message and send it to queue **INCOMING**
2. Add header to JMS message and send multiple copies to queue **NEXT**
3. Filter all unique JMS messages and send them to queue **UNIQUE**
4. receive all JMS messages and send them to queue **PROCESSED**

No time is spend to prevent hardcoded queue names, etc.

## Run
- Start JMS queue with docker-compose file (environment/docker directory)
- start application with class 'JmsCamelApplication'.
- [Redis GUI](http://localhost:5540/) to access the Redis database