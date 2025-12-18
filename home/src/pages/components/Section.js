import React from 'react'

export default function Section({children }) {
    return (
        <section className="py-20 w-full bg-gradient-to-b from-white via-purple-50 to-white dark:from-gray-900 dark:via-purple-950 dark:to-gray-900">
            <div className="container mx-auto px-4">
                <div className="row">
                    {children}
                </div>
            </div>
        </section>
    )
}

