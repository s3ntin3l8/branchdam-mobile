# Contributing Guide

## Branching & Commit Guidelines

- **Branch off the latest remote default branch, never off your local one:**
  ```sh
  git fetch origin && git checkout -b feat/my-feature origin/main
  ```
- **No direct commits to `main`:** All changes must go through pull requests.
- **One Issue = One PR:** Keep PR scopes aligned strictly with the issue blueprint.

## Automated Reviews with Hermes

This repo runs automated code review via **Hermes**.
- On PR open or ready for review, Hermes reviews changes automatically.
- To request a re-review or triage, comment:
  ```
  @s3ntin3l8-hermes Review
  ```
