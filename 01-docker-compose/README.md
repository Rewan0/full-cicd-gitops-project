# Containerized Node.js and MySQL Stack Using Docker Compose

## Objective

Run a Node.js application together with a MySQL database as a multi-container stack using Docker Compose, connect them through a custom network, persist application logs and database data using volumes, and push the application image to Docker Hub.

## Tools and Technologies

- Docker
- Docker Compose
- Node.js 18 (Alpine image)
- MySQL 8.0
- Docker Hub

## Project Structure

```text
Lab8/
├── Dockerfile
├── docker-compose.yml
├── server.js
├── db.js
├── package.json
├── frontend/
└── README.md
```

## Step 1: Clone the Repository

```bash
git clone https://github.com/Ibrahim-Adel15/iVolveApp.git
cd iVolveApp
```

The repository already contains a `Dockerfile` that builds the application on `node:18-alpine` and starts it with `node server.js`.

## Step 2: Check the Application Requirements

The application needs a MySQL connection and a database named `ivolve` to start successfully. Check which environment variables the application reads:

```bash
grep -rn "process.env" --include=*.js . --exclude-dir=node_modules
```

The application uses these variables:

| Variable | Purpose |
|---|---|
| `DB_HOST` | MySQL host name |
| `DB_USER` | Database user |
| `DB_PASSWORD` | Database password |
| `DB_NAME` | Database name |

The application also writes its access logs to `/app/logs/access.log`, and it retries the database connection every 5 seconds until the `ivolve` database exists.

## Step 3: Create the Docker Compose File

Create `docker-compose.yml`:

```yaml
services:
  app:
    build: .
    container_name: ivolve-app
    ports:
      - "3000:3000"
    environment:
      DB_HOST: db
      DB_USER: root
      DB_PASSWORD: rootpass
      DB_NAME: ivolve
    volumes:
      - app_logs:/app/logs
    networks:
      - ivolve-net
    depends_on:
      - db

  db:
    image: mysql:8.0
    container_name: ivolve-db
    environment:
      MYSQL_ROOT_PASSWORD: rootpass
      MYSQL_DATABASE: ivolve
    volumes:
      - db_data:/var/lib/mysql
    networks:
      - ivolve-net

networks:
  ivolve-net:
    name: ivolve-net
    driver: bridge

volumes:
  db_data:
    name: db_data
  app_logs:
    name: app_logs
```

The Compose file defines:
- An `app` service built from the Dockerfile, exposed on port 3000, with the database environment variables.
- A `db` service using MySQL 8.0 that creates the `ivolve` database on first start.
- A custom network named `ivolve-net` used by both services.
- Two named volumes: `app_logs` mounted at `/app/logs` and `db_data` mounted at `/var/lib/mysql`.

The application connects to the database using `DB_HOST=db`, because the service name is resolved as the host name inside the custom network.

The explicit `name:` values prevent Docker Compose from adding the project name as a prefix (for example `ivolveapp_ivolve-net`), so the network and volumes use the exact names required by the lab.

## Step 4: Start the Services

```bash
docker compose up -d --build
```

Check the containers:

```bash
docker compose ps
```

## Step 5: Verify the Application and Database Connection

Check the application logs:

```bash
docker compose logs app
```

The logs should show that the application connected to MySQL and found the `ivolve` database. A few `ECONNREFUSED` messages at startup are expected while MySQL is still initializing.

Test the health and readiness endpoints:

```bash
curl http://localhost:3000/health
curl http://localhost:3000/ready
```

Verify that the `ivolve` database exists:

```bash
docker exec -it ivolve-db mysql -uroot -prootpass -e "SHOW DATABASES;"
```

Verify the network and volumes:

```bash
docker network inspect ivolve-net
docker volume ls | grep -E "db_data|app_logs"
```

Verify that the application logs are written to the volume:

```bash
docker exec ivolve-app cat /app/logs/access.log
```

You can also open `http://localhost:3000` in the browser.

## Step 6: Test Data Persistence

Remove the containers without removing the volumes, then start them again:

```bash
docker compose down
docker compose up -d
docker exec ivolve-app cat /app/logs/access.log
```

The previous logs should still exist because they are stored in the `app_logs` volume.

## Step 7: Tag and Push the Image to Docker Hub

Log in to Docker Hub:

```bash
docker login
```

Tag the application image with the Docker Hub username:

```bash
docker tag ivolveapp-app:latest rewan2233/ivolve-app:v1
```

Push the image:

```bash
docker push rewan2233/ivolve-app:v1
```

Verify the local images:

```bash
docker images | grep ivolve
```

Image repository: `docker.io/rewan2233/ivolve-app:v1`

## Screenshots

### Docker Compose File
<img width="1318" height="957" alt="image" src="https://github.com/user-attachments/assets/5d4f8091-947f-4ba5-9e3b-5e5b93c1c2f8" />

### Start the Services
<img width="2560" height="1528" alt="image" src="https://github.com/user-attachments/assets/1aac1679-7189-41e0-b39b-f49596072d9d" />

### Application Logs and Database Connection
<img width="2559" height="900" alt="image" src="https://github.com/user-attachments/assets/24da892d-d182-4a67-8626-4065806c1191" />

### Health and Readiness Endpoints
<img width="2011" height="139" alt="image" src="https://github.com/user-attachments/assets/f56cbceb-0469-45c3-ac00-eb5d529781cb" />

### Network and Volumes
<img width="2560" height="1528" alt="image" src="https://github.com/user-attachments/assets/38c4615e-c8c0-4532-8581-f9e87f1e4c7d" />

### Application in the Browser
<img width="2560" height="1528" alt="image" src="https://github.com/user-attachments/assets/59dd1e67-b542-4884-bdab-92a4eb46f4e8" />

### Push the Image to Docker Hub
<img width="2559" height="622" alt="image" src="https://github.com/user-attachments/assets/51023ddb-94ba-4033-99f9-f2b41115ea1d" />
<img width="1280" height="764" alt="Screenshot 2026-10-24 235241" src="https://github.com/user-attachments/assets/127b9f53-ee2b-4579-8c7e-c535a1d0b85b" />


## Conclusion

This lab demonstrates how Docker Compose can run a Node.js application and a MySQL database as one stack. The services communicate through a custom network using service names, while application logs and database data are preserved in named volumes. It also demonstrates tagging the application image with a Docker Hub username and pushing it to a remote repository.
