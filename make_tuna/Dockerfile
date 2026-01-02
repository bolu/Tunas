FROM python

# Install system dependencies
RUN apt-get update && \
    apt-get install -y ffmpeg

# Set working directory
WORKDIR /project

# Install Python dependencies
RUN pip install \
    Cython \
    numpy \
    scipy \
    mido \
    soundfile \
    librosa \
    pydub

# Install madmom from GitHub (if needed for latest version)
RUN pip install git+https://github.com/CPJKU/madmom.git

# Default command
CMD ["python", "make_tuna.py"]

# Build the image
# docker build -t make-tuna .
#
# Run the container
# docker run --name make-tuna -v .:/project make-tuna
#
# Or run interactively
# docker run --name make-tuna -i -t -v .:/project make-tuna bash
