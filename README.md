<p align="center">

<br />

<img src="https://github.com/streamarr/streamarr-ux/blob/main/branding/assets/streamarr-logo-text.svg" width="425" alt="Streamarr logo">

<br />
<br />
<br />

<a href="https://github.com/streamarr/streamarr-server/blob/main/LICENSE">
<img alt="GPL 3.0 License" src="https://img.shields.io/badge/license-GPL--3.0-orange.svg"/>
</a>

<a href="https://sonarcloud.io/summary/new_code?id=streamarr_streamarr-server">
<img alt="Code Coverage" src="https://sonarcloud.io/api/project_badges/measure?project=streamarr_streamarr-server&metric=coverage"/>
</a>

<a href="https://sonarcloud.io/summary/new_code?id=streamarr_streamarr-server">
<img alt="Security Rating" src="https://sonarcloud.io/api/project_badges/measure?project=streamarr_streamarr-server&metric=security_rating"/>
</a>

</p>

### Running in development:

#### Step 1 - Build PG Docker Image:

`docker compose build`

#### Step 2 - Start PG Docker Container:

`docker compose up -d`

#### Step 3 - Ensure JAVA_HOME = JDK 17 (_MACOS example_)

`jenv local 17.0.3`

#### Step 4 - Start Streamarr Server

`./mvnw spring-boot:run`

### To build docker image

`./mvnw spring-boot:build-image`

Image will look like:
'docker.io/library/server:0.0.1-SNAPSHOT'
