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

mkdir -p artifacts/
echo "Fetching artifacts directly from GitHub API..."
gh api "repos/$GITHUB_REPOSITORY/actions/runs/$GITHUB_RUN_ID/artifacts" --jq '.artifacts[] | "\(.id) \(.name)"' | while read -r id name; do
	echo "Downloading $name (id: $id)..."
	gh api "repos/$GITHUB_REPOSITORY/actions/artifacts/$id/zip" > "artifacts/$name"
done

pushd artifacts/ || exit 1
echo "Downloaded artifacts:"
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
