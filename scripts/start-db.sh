#!/usr/bin/env bash
# Starts the development PostgreSQL database in Docker, listening on localhost:5433.
# The credentials match the defaults in src/main/resources/application.yml.
# Data is kept in a named Docker volume, so it survives restarts of the container.
set -euo pipefail

CONTAINER_NAME="site-archive-postgres"
VOLUME_NAME="site-archive-postgres-data"
HOST_PORT=5433
DB_NAME="site_archive"
DB_USER="site_archive"
DB_PASSWORD="site_archive"

if docker container inspect "$CONTAINER_NAME" > /dev/null 2>&1; then
    echo "Starting existing container $CONTAINER_NAME"
    docker start "$CONTAINER_NAME" > /dev/null
else
    echo "Creating container $CONTAINER_NAME"
    docker run --detach \
        --name "$CONTAINER_NAME" \
        --publish "$HOST_PORT:5432" \
        --volume "$VOLUME_NAME:/var/lib/postgresql/data" \
        --env POSTGRES_DB="$DB_NAME" \
        --env POSTGRES_USER="$DB_USER" \
        --env POSTGRES_PASSWORD="$DB_PASSWORD" \
        postgres:17 > /dev/null
fi

echo -n "Waiting for PostgreSQL"
for _ in $(seq 1 30); do
    # Check over TCP: on first start the image runs a temporary, socket-only server while it initialises.
    if docker exec "$CONTAINER_NAME" pg_isready --quiet --host 127.0.0.1 --username "$DB_USER" --dbname "$DB_NAME"; then
        echo
        echo "PostgreSQL is ready at jdbc:postgresql://localhost:$HOST_PORT/$DB_NAME"
        echo "Stop it with: docker stop $CONTAINER_NAME"
        exit 0
    fi
    echo -n "."
    sleep 1
done

echo
echo "PostgreSQL did not become ready in time; see: docker logs $CONTAINER_NAME" >&2
exit 1
