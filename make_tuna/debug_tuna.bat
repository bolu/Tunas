@echo off
echo Full path: %~f1
echo Full directory: %~dp1
echo Filename: %~nx1
echo Make tuna lives in: %~dp0

docker desktop start

docker run --rm -i -t -v "%~dp1:/project" -v "%~dp0:/app" make-tuna python ../app/debug_tuna.py "%~nx1"

pause