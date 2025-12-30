@echo off
if not defined MAXED (
    set MAXED=1
    start /max "" "%~f0" %*
    exit /b
)
cmd
exit
