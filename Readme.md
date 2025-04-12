# Gemini code chatbot

This chatbot utilizes Gemini model. It can do web search and run simple Python computations. 

## Environment settings

The following environment variables are required to run the agent.

- GOOGLE_CLOUD_REGION: Google cloud region where Gemini model is run, for example "us-central1".
- GOOGLE_CLOUD_PROJECT: Google cloud project ID used for billing Gemini models.
- GOOGLE_APPLICATION_CREDENTIALS: Path to GCP service account's JSON credential file which is used to run the application, see https://cloud.google.com/iam/docs/keys-create-delete. 
- GOOGLE_SEARCH_API_KEY: Custom Search JSON API key, see https://developers.google.com/custom-search/v1/introduction.
- GOOGLE_SEARCH_ENGINE_ID: Custom Search Engine ID.
- GOOGLE_VERTEXAI_MODEL_PRO: Gemini model name from Vertex AI Model Garden, for example "gemini-2.5-pro-exp-03-25".
- GOOGLE_VERTEXAI_MODEL_FAST: Gemini model name from Vertex AI Model Garden, for example "gemini-2.0-flash-001". 

## Run application

1. Install SBT https://www.scala-sbt.org/.
2. Clone sources.
3. Change current directory to the project root directory where *build.sbt* is.
4. Run *sbt "run service --host localhost --port 8080"*.
5. Open in the browser *http://localhost:8080/chat*.


## Miscelaneous...
Application logs are sent to STDERR and formatted in a way suitable for GCP easy parsing.

Some other useful REST end points:
* ```GET /chat``` is the agent's chat page.
* ```POST /admin/shutdown``` to gracefully shutdown application.
* ```GET /health``` is simple health check.

