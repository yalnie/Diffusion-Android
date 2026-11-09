#!/bin/bash

if ! command -v gh > /dev/null; then
	echo "Install GitHub CLI tool"
	exit 1
fi

RELEASE_NAME=$1
BRANCH_NAME=$2
DEFAULT_BRANCH_NAME=master

if [ -z "$RELEASE_NAME" ] || [ -z "$BRANCH_NAME" ]; then
	echo "Invalid arguments"
	exit 1
fi

if [ "$BRANCH_NAME" == "$DEFAULT_BRANCH_NAME" ]; then
	RELEASE_TAG="continuous"
else
	RELEASE_TAG="continuous-$BRANCH_NAME"
fi

gh release delete "$RELEASE_TAG" \
	--yes \
	--cleanup-tag \
	--repo "$GITHUB_REPOSITORY" || true

gh run download "$GITHUB_RUN_ID" \
	--dir artifacts/ \
	--repo "$GITHUB_REPOSITORY"

pushd artifacts/ || exit 1
echo "Found artifacts:"
ls -la
# Flatten any subdirectories without re-zipping or extracting
find . -mindepth 2 -type f -exec mv -t . {} +
find . -mindepth 1 -type d -exec rm -rf {} +
echo "Repackaged artifacts:"
ls -la
popd || exit 1

#echo "Generated notes:"
#rm release-notes && touch release-notes
#if [ -n "$BASE_BUILDNUM_DATE" ]; then
#	LAST_COMMIT_DATE=$(git log -1 --format=%ci)
#	BUILDNUM=$(( ( $(date -d "$LAST_COMMIT_DATE" +%s ) - $(date -d "$BASE_BUILDNUM_DATE" +%s) ) / 86400 ))
#	echo "buildnum $BUILDNUM" | tee -a release-notes
#fi

sleep 10s
shopt -s nullglob
FILES_TO_UPLOAD=(artifacts/*)
FILES_ONLY=()
for f in "${FILES_TO_UPLOAD[@]}"; do
	if [ -f "$f" ]; then
		FILES_ONLY+=("$f")
	fi
done

gh release create "$RELEASE_TAG" "${FILES_ONLY[@]}" \
	--title "$RELEASE_NAME Continuous $BRANCH_NAME Build" \
	--target "$GITHUB_SHA" \
	--repo "$GITHUB_REPOSITORY" \
	--prerelease

#rm release-notes
