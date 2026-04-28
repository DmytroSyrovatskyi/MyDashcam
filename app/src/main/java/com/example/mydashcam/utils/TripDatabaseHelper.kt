package com.example.mydashcam.utils

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

// Структура данных (Бланк одной поездки)
data class TripRecord(
    val id: Long = 0,
    val startTime: Long,
    val endTime: Long,
    val distanceMeters: Float,
    val maxSpeedKmh: Float,
    val maxGForce: Float
)

class TripDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "trip_journal.db" // Название файла базы данных
        private const val DATABASE_VERSION = 1
        private const val TABLE_TRIPS = "trips"

        // Названия колонок
        private const val COLUMN_ID = "id"
        private const val COLUMN_START_TIME = "start_time"
        private const val COLUMN_END_TIME = "end_time"
        private const val COLUMN_DISTANCE = "distance_meters"
        private const val COLUMN_MAX_SPEED = "max_speed_kmh"
        private const val COLUMN_MAX_G = "max_g_force"
    }

    override fun onCreate(db: SQLiteDatabase) {
        // Создаем таблицу при первом запуске
        val createTable = ("CREATE TABLE $TABLE_TRIPS ("
                + "$COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "$COLUMN_START_TIME INTEGER,"
                + "$COLUMN_END_TIME INTEGER,"
                + "$COLUMN_DISTANCE REAL,"
                + "$COLUMN_MAX_SPEED REAL,"
                + "$COLUMN_MAX_G REAL)")
        db.execSQL(createTable)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_TRIPS")
        onCreate(db)
    }

    // ФУНКЦИЯ 1: Сохранить поездку в базу (Вызовем при остановке записи)
    fun insertTrip(trip: TripRecord): Long {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_START_TIME, trip.startTime)
            put(COLUMN_END_TIME, trip.endTime)
            put(COLUMN_DISTANCE, trip.distanceMeters)
            put(COLUMN_MAX_SPEED, trip.maxSpeedKmh)
            put(COLUMN_MAX_G, trip.maxGForce)
        }
        val id = db.insert(TABLE_TRIPS, null, values)
        db.close()
        return id
    }

    // ФУНКЦИЯ 2: Получить историю (Вызовем, когда откроем Журнал)
    fun getAllTrips(): List<TripRecord> {
        val tripList = mutableListOf<TripRecord>()
        val selectQuery = "SELECT * FROM $TABLE_TRIPS ORDER BY $COLUMN_START_TIME DESC" // Самые новые сверху
        val db = this.readableDatabase
        val cursor = db.rawQuery(selectQuery, null)

        if (cursor.moveToFirst()) {
            do {
                val trip = TripRecord(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_ID)),
                    startTime = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_START_TIME)),
                    endTime = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_END_TIME)),
                    distanceMeters = cursor.getFloat(cursor.getColumnIndexOrThrow(COLUMN_DISTANCE)),
                    maxSpeedKmh = cursor.getFloat(cursor.getColumnIndexOrThrow(COLUMN_MAX_SPEED)),
                    maxGForce = cursor.getFloat(cursor.getColumnIndexOrThrow(COLUMN_MAX_G))
                )
                tripList.add(trip)
            } while (cursor.moveToNext())
        }
        cursor.close()
        db.close()
        return tripList
    }

    // ФУНКЦИЯ 3: Сбросить одометр (Удалить всё)
    fun clearAllTrips() {
        val db = this.writableDatabase
        db.execSQL("DELETE FROM $TABLE_TRIPS")
        db.close()
    }
}