# streamarr-server

### Running in development:

#### Step 1 - Build PG Docker Image:

`docker compose build`

#### Step 2 - Start PG Docker Container:

`docker compose up -d`

#### Step 3 - Ensure JAVA_HOME = JDK 17 (_MACOS example_)

`jenv local 17.0.3`

#### Step 4 - Start Streamarr Server

`./mvnw spring-boot:run`
