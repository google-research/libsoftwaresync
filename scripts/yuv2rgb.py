# coding=utf-8
# Copyright 2019 The Google Research Authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Process raw NV21 images to jpg or png images using ffmpeg.

This script requires ffmpeg (https://www.ffmpeg.org/download.html).

Run with:
python3 yuv2rgb.py img_<timestamp>.nv21 nv21_metadata_<timestamp>.txt
out.<png|jpg>.
"""

import argparse
import subprocess


def parse_meta(path):
  with open(path, 'r') as f:
    lines = [l.strip() for l in f.readlines()]
    img_width = lines[0].split(' ')[1]
    img_height = lines[1].split(' ')[1]
    img_pixel_format = lines[2].split(' ')[1]
  return img_width, img_height, img_pixel_format


if __name__ == '__main__':
  parser = argparse.ArgumentParser()
  parser.add_argument(
      '-y', '--overwrite', help='Overwrite output.', action='store_true')
  parser.add_argument('input')
  parser.add_argument('meta')
  parser.add_argument('output', help='output filename (ending in .jpg or .png)')
  args = parser.parse_args()

  if not args.output.endswith('.jpg') and not args.output.endswith('.png'):
    raise ValueError('output must end in jpg or png.')

  width, height, pixel_format = parse_meta(args.meta)
  pixel_format = pixel_format.lower()

  overwrite_flag = '-y' if args.overwrite else '-n'

  cmd = [
      'ffmpeg', overwrite_flag, '-f', 'image2', '-vcodec', 'rawvideo',
      '-pix_fmt', pixel_format, '-s', f'{width}x{height}', '-i', args.input,
      args.output
  ]

  subprocess.call(cmd)
