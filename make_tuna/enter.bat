@echo off
echo Make tuna lives in: %~dp0

docker desktop start

docker run --rm -i -t -v "%~dp0:/app" make-tuna bash

pause