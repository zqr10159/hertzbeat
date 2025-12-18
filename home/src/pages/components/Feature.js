import React from 'react'
import clsx from 'clsx'

export default function Feature({ title, description }) {
    return (
        <div className={clsx('col col--4', 'mb-8')}>
            <div className="h-full p-6 rounded-2xl bg-gradient-to-br from-purple-50 via-pink-50 to-blue-50 dark:from-gray-800 dark:via-purple-900 dark:to-gray-900 shadow-lg hover:shadow-2xl transition-all duration-300 transform hover:-translate-y-2 border border-purple-100 dark:border-purple-800">
                <div className="mb-4 h-16 w-16 mx-auto bg-gradient-to-br from-purple-600 to-pink-600 rounded-xl flex items-center justify-center text-white text-2xl font-bold shadow-lg">
                    ✨
                </div>
                <h3 className="text-center text-2xl font-bold mb-4 bg-gradient-to-r from-purple-600 to-pink-600 bg-clip-text text-transparent">{title}</h3>
                <p className="text-center text-gray-700 dark:text-gray-300 leading-relaxed">{description}</p>
            </div>
        </div>
    )
}
