#!/bin/sh
# Starts the GoEV backend on Render (or any container host).
set -eu

# Render's Postgres "connection string" is postgres://user:pass@host:port/db, which JDBC
# cannot use directly. Convert it unless JDBC_DATABASE_URL was given explicitly.
if [ -z "${JDBC_DATABASE_URL:-}" ] && [ -n "${DATABASE_URL:-}" ]; then
  case "$DATABASE_URL" in
    jdbc:*)
      JDBC_DATABASE_URL="$DATABASE_URL"
      ;;
    postgres://*|postgresql://*)
      rest="${DATABASE_URL#*://}"            # user:pass@host:port/db?params
      creds="${rest%@*}"                      # user:pass (split on the last @)
      hostpart="${rest##*@}"                  # host:port/db?params
      # Neon adds channel_binding=require, which the JDBC driver does not understand; drop it.
      case "$hostpart" in
        *\?*)
          base="${hostpart%%\?*}"
          query=$(printf '%s' "${hostpart#*\?}" | tr '&' '\n' | grep -v '^channel_binding=' | paste -sd '&' -)
          hostpart="$base${query:+?$query}"
          ;;
      esac
      JDBC_DATABASE_URL="jdbc:postgresql://${hostpart}"
      : "${DATABASE_USERNAME:=${creds%%:*}}"
      : "${DATABASE_PASSWORD:=${creds#*:}}"
      export DATABASE_USERNAME DATABASE_PASSWORD
      ;;
    *)
      echo "Unrecognised DATABASE_URL scheme; set JDBC_DATABASE_URL instead." >&2
      exit 1
      ;;
  esac
  export JDBC_DATABASE_URL
fi

case "${GOEV_APP:-full}" in
  full)     JAR=/app/goev-full.jar ;;
  research) JAR=/app/goev-research.jar ;;
  *) echo "GOEV_APP must be 'full' or 'research' (got '${GOEV_APP}')." >&2; exit 1 ;;
esac

echo "Starting GoEV (${GOEV_APP:-full}) on port ${PORT:-8080}"
# shellcheck disable=SC2086
exec java ${JAVA_OPTS:-} -Dserver.port="${PORT:-8080}" -jar "$JAR"
