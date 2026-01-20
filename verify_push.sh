#!/bin/bash
git status > push_result.txt 2>&1
echo "---LOCAL HEAD---" >> push_result.txt
git log -1 --format="%H" >> push_result.txt 2>&1
echo "---REMOTE HEAD---" >> push_result.txt
git ls-remote origin main >> push_result.txt 2>&1
