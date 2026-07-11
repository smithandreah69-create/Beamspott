# Use official Node.js lightweight image
FROM node:18-alpine

# Set working directory
WORKDIR /app

# Copy package files first for caching
COPY package*.json ./

# Install production dependencies
RUN npm ci --only=production

# Copy application source and assets
COPY src/ ./src/
COPY public/ ./public/

# Expose port (Render sets PORT dynamically, falls back to 3000 in server.js)
EXPOSE 3000

# Start the application
CMD ["npm", "start"]
