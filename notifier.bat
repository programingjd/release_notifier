@echo off
pushd %~dp0build\libs
for %%j in (*.jar) do (
  popd
  "%JAVA_HOME%\bin\java" -jar "%~dp0build\libs\%%j" %*
  goto :eof
)


