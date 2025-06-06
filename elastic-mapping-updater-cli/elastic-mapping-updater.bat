@REM
@REM Copyright 2020-2025 Open Text.
@REM
@REM Licensed under the Apache License, Version 2.0 (the "License");
@REM you may not use this file except in compliance with the License.
@REM You may obtain a copy of the License at
@REM
@REM      http://www.apache.org/licenses/LICENSE-2.0
@REM
@REM Unless required by applicable law or agreed to in writing, software
@REM distributed under the License is distributed on an "AS IS" BASIS,
@REM WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
@REM See the License for the specific language governing permissions and
@REM limitations under the License.
@REM

@echo off
setlocal

:: Use the Maven exec plugin to get the class path
for /f "delims=" %%i in ('"mvn -q -f "%~dp0pom.xml" org.codehaus.mojo:exec-maven-plugin:1.6.0:exec -Dexec.executable=cmd -Dexec.args="/c echo %%classpath""') do set _PROJ_CLASSPATH=%%i

:: Execute the program
java -classpath %_PROJ_CLASSPATH% com.github.cafdataprocessing.elastic.tools.cli.Program %*
