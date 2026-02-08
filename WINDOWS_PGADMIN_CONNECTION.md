# 🗄️ Connecting Windows pgAdmin to Docker PostgreSQL

## Prerequisites

1. **PostgreSQL Docker container is running:**
   ```bash
   docker-compose up -d
   ```

2. **Verify PostgreSQL is accessible:**
   ```bash
   docker ps
   ```
   You should see `flash-sale-postgres` container running.

## 🔌 Connection Steps

### Step 1: Open pgAdmin

Launch pgAdmin 4 from your Windows Start menu.

### Step 2: Add New Server

1. Right-click on **"Servers"** in the left sidebar
2. Select **"Register"** → **"Server..."**

### Step 3: General Tab

- **Name:** `Flash Sale Database` (or any name you prefer)

### Step 4: Connection Tab

Fill in the following connection details:

| Field | Value |
|-------|-------|
| **Host name/address** | `localhost` or `127.0.0.1` |
| **Port** | `5432` |
| **Maintenance database** | `flashsale` |
| **Username** | `flashuser` |
| **Password** | `secret` |
| **Save password?** | ✅ Check this box (optional, for convenience) |

### Step 5: Advanced Tab (Optional)

- **DB restriction:** Leave empty (to see all databases) or enter `flashsale` to restrict to this database only

### Step 6: Save

Click **"Save"** button at the bottom.

You should now see the `flashsale` database in the left sidebar!

## ✅ Verify Connection

1. Expand: **Servers** → **Flash Sale Database** → **Databases** → **flashsale** → **Schemas** → **public** → **Tables**
2. You should see:
   - `inventory` table
   - `orders` table

## 📊 Quick Test Queries

Right-click on the `flashsale` database → **"Query Tool"** and try:

```sql
-- Check inventory
SELECT * FROM inventory;

-- Check all orders
SELECT * FROM orders;

-- Check remaining stock
SELECT product_name, stock_quantity FROM inventory;
```

## 🔍 Useful Queries for Week 1 Testing

### Check if overselling occurred:

```sql
-- This will show negative stock if race condition happened
SELECT id, product_name, stock_quantity, version 
FROM inventory 
WHERE stock_quantity < 0;

-- Count total orders vs available stock
SELECT 
    i.product_name,
    i.stock_quantity as remaining_stock,
    COUNT(o.id) as total_orders,
    COUNT(CASE WHEN o.status = 'CONFIRMED' THEN 1 END) as confirmed_orders
FROM inventory i
LEFT JOIN orders o ON o.product_id = i.id
GROUP BY i.id, i.product_name, i.stock_quantity;
```

### Reset inventory for re-testing:

```sql
-- Reset stock to 100
UPDATE inventory SET stock_quantity = 100, version = 0 WHERE id = 1;

-- Clear all orders
DELETE FROM orders;
```

## ⚠️ Troubleshooting

### "Could not connect to server"

**Problem:** pgAdmin can't connect to PostgreSQL

**Solutions:**
1. **Check if Docker container is running:**
   ```bash
   docker ps
   ```
   If `flash-sale-postgres` is not listed, start it:
   ```bash
   docker-compose up -d
   ```

2. **Check if port 5432 is available:**
   ```bash
   netstat -ano | findstr :5432
   ```
   If another PostgreSQL instance is using port 5432, either:
   - Stop the other instance, OR
   - Change the port mapping in `compose.yaml` (e.g., `'5433:5432'`)

3. **Verify PostgreSQL is ready:**
   ```bash
   docker-compose logs postgres
   ```
   Look for "database system is ready to accept connections"

4. **Test connection from command line:**
   ```bash
   docker exec -it flash-sale-postgres psql -U flashuser -d flashsale
   ```
   If this works, the issue is with pgAdmin connection settings.

### "Password authentication failed"

- Double-check the password: `secret`
- Make sure username is: `flashuser` (not `postgres`)

### "Database does not exist"

- Make sure you're connecting to database: `flashsale`
- The database is created automatically when the container starts

### Connection works but no tables visible

- Make sure the Spring Boot application has run at least once (it creates the tables)
- Check if you're looking in the right schema: `public` schema under `flashsale` database

## 🔐 Connection Details Summary

| Item | Value |
|------|-------|
| **Host** | `localhost` or `127.0.0.1` |
| **Port** | `5432` |
| **Database** | `flashsale` |
| **Username** | `flashuser` |
| **Password** | `secret` |

## 💡 Pro Tips

1. **Save the connection:** Check "Save password" to avoid entering it every time
2. **Use Query Tool:** Right-click database → Query Tool for quick SQL execution
3. **Auto-refresh:** Right-click tables → Refresh to see latest data after running tests
4. **Export data:** Right-click table → Backup/Export to save query results

---

**Happy Querying!** 🎉
