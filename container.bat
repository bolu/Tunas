docker desktop start
docker run --name tunas-build-box -t -d -v .:/project mingc/android-build-box
docker start tunas-build-box